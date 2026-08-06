"""受限网页抽取：仅 http(s)、超时与大小上限。"""

from __future__ import annotations

import logging
import re
from html import unescape
from typing import Any
from urllib.parse import urlparse

import httpx

logger = logging.getLogger(__name__)

_MAX_BYTES = 512_000
_TIMEOUT = 12.0


def _strip_html(html: str) -> str:
    """粗粒度去标签，保留可读正文。"""
    text = re.sub(r"(?is)<script[^>]*>.*?</script>", " ", html)
    text = re.sub(r"(?is)<style[^>]*>.*?</style>", " ", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:8000]


def fetch_url(url: str) -> dict[str, Any] | None:
    """
    抓取并抽取正文。

    Returns:
        {title, url, snippet, sourceType=WEB}；失败返回 None（不抛垮调用方）。
    """
    try:
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return None
        with httpx.Client(timeout=_TIMEOUT, follow_redirects=True) as client:
            resp = client.get(url, headers={"User-Agent": "InsightHubBot/0.1"})
            if resp.status_code >= 400:
                return None
            raw = resp.content[:_MAX_BYTES]
            ctype = (resp.headers.get("content-type") or "").lower()
            if "html" not in ctype and "text" not in ctype:
                return None
            html = raw.decode(resp.encoding or "utf-8", errors="replace")
        title_m = re.search(r"(?is)<title[^>]*>(.*?)</title>", html)
        title = _strip_html(title_m.group(1)) if title_m else url
        body = _strip_html(html)
        if len(body) < 40:
            return None
        return {
            "title": title[:200],
            "url": url,
            "snippet": body[:600],
            "sourceType": "WEB",
        }
    except Exception as exc:  # noqa: BLE001
        logger.info("web_fetch failed for %s: %s", url, exc)
        return None
