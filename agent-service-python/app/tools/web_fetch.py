"""受限网页抽取：仅 http(s)、超时与大小上限。"""

from __future__ import annotations

import logging
import ipaddress
import re
import socket
from html import unescape
from typing import Any
from urllib.parse import urljoin, urlparse, urlunparse

import httpx

logger = logging.getLogger(__name__)

_MAX_BYTES = 512_000
_TIMEOUT = 12.0
_MAX_REDIRECTS = 3


def _decode_html(raw: bytes, declared: str | None, content_type: str) -> str:
    """
    按优先级解码 HTML：UTF-8 → Content-Type charset → 声明编码 → latin-1。
    避免一律 errors=replace 把合法多字节中文打成 U+FFFD。
    """
    candidates: list[str] = ["utf-8"]
    # Content-Type: text/html; charset=gbk
    m = re.search(r"charset=([\w\-]+)", content_type or "", flags=re.I)
    if m:
        candidates.append(m.group(1).strip().lower())
    if declared:
        candidates.append(declared.strip().lower())
    candidates.append("gb18030")

    seen: set[str] = set()
    for enc in candidates:
        if not enc or enc in seen:
            continue
        seen.add(enc)
        try:
            return raw.decode(enc)
        except (LookupError, UnicodeDecodeError):
            continue
    # 最后兜底：保留字节语义，不再制造 U+FFFD
    return raw.decode("latin-1")


def _prepare_public_request(url: str) -> tuple[str, str, str] | None:
    """校验公网目标，并返回固定 IP 的请求 URL、Host 与 SNI。"""
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None
    if parsed.username or parsed.password:
        return None
    host = parsed.hostname.rstrip(".").lower()
    if host == "localhost" or host.endswith(".localhost") or host.endswith(".local"):
        return None
    try:
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        addresses = [
            item[4][0]
            for item in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        ]
    except (OSError, UnicodeError, ValueError):
        return None
    if not addresses:
        return None

    public_addresses: list[ipaddress.IPv4Address | ipaddress.IPv6Address] = []
    try:
        for address in addresses:
            parsed_address = ipaddress.ip_address(address.split("%", 1)[0])
            if not parsed_address.is_global:
                return None
            if parsed_address not in public_addresses:
                public_addresses.append(parsed_address)
    except ValueError:
        return None

    selected = public_addresses[0]
    selected_host = f"[{selected.compressed}]" if selected.version == 6 else selected.compressed
    default_port = 443 if parsed.scheme == "https" else 80
    target_netloc = selected_host if port == default_port else f"{selected_host}:{port}"

    try:
        original_ip = ipaddress.ip_address(host)
        host_name = f"[{original_ip.compressed}]" if original_ip.version == 6 else original_ip.compressed
    except ValueError:
        try:
            host_name = host.encode("idna").decode("ascii")
        except UnicodeError:
            return None
    host_header = host_name if port == default_port else f"{host_name}:{port}"
    request_url = urlunparse(parsed._replace(netloc=target_netloc, fragment=""))
    return request_url, host_header, host_name.strip("[]")


def _is_public_http_url(url: str) -> bool:
    """校验 URL 及其 DNS 结果均为公网地址。"""
    return _prepare_public_request(url) is not None


def _strip_html(html: str) -> str:
    """粗粒度去标签，保留可读正文。"""
    text = re.sub(r"(?is)<script[^>]*>.*?</script>", " ", html)
    text = re.sub(r"(?is)<style[^>]*>.*?</style>", " ", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:8000]


def fetch_url(url: str, timeout_seconds: float = _TIMEOUT) -> dict[str, Any] | None:
    """
    抓取并抽取正文。

    Returns:
        {title, url, snippet, sourceType=WEB}；失败返回 None（不抛垮调用方）。
    """
    try:
        if timeout_seconds <= 0:
            raise TimeoutError("agent task timed out")
        current_url = url
        html = ""
        with httpx.Client(timeout=timeout_seconds, follow_redirects=False, trust_env=False) as client:
            for redirect_count in range(_MAX_REDIRECTS + 1):
                prepared = _prepare_public_request(current_url)
                if prepared is None:
                    return None
                request_url, host_header, sni_hostname = prepared
                with client.stream(
                    "GET",
                    request_url,
                    headers={"Host": host_header, "User-Agent": "InsightHubBot/0.1"},
                    extensions={"sni_hostname": sni_hostname},
                ) as resp:
                    if resp.status_code in {301, 302, 303, 307, 308}:
                        location = resp.headers.get("location")
                        if not location or redirect_count >= _MAX_REDIRECTS:
                            return None
                        current_url = urljoin(current_url, location)
                        continue
                    if resp.status_code >= 400:
                        return None
                    ctype = (resp.headers.get("content-type") or "").lower()
                    if "html" not in ctype and "text" not in ctype:
                        return None
                    declared = resp.headers.get("content-length")
                    if declared and int(declared) > _MAX_BYTES:
                        return None
                    chunks: list[bytes] = []
                    size = 0
                    for chunk in resp.iter_bytes():
                        size += len(chunk)
                        if size > _MAX_BYTES:
                            return None
                        chunks.append(chunk)
                    raw = b"".join(chunks)
                    # 优先 UTF-8；声明编码失败时再回退，避免 errors=replace 把中文打成 U+FFFD
                    html = _decode_html(raw, resp.encoding, ctype)
                    break
            else:
                return None
        title_m = re.search(r"(?is)<title[^>]*>(.*?)</title>", html)
        title = _strip_html(title_m.group(1)) if title_m else url
        body = _strip_html(html)
        if len(body) < 40:
            return None
        return {
            "title": title[:200],
            "url": current_url,
            "snippet": body[:600],
            "sourceType": "WEB",
        }
    except Exception as exc:  # noqa: BLE001
        logger.info("web_fetch failed for %s: %s", url, exc)
        return None
