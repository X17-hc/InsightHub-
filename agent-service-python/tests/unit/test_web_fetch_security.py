"""网页抓取 SSRF 防护测试。"""

from __future__ import annotations

import socket

from app.tools import web_fetch


def _addr(address: str):
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 80))]


def test_rejects_localhost_and_private_dns(monkeypatch):
    assert not web_fetch._is_public_http_url("http://localhost/admin")
    monkeypatch.setattr(socket, "getaddrinfo", lambda *_a, **_k: _addr("10.0.0.8"))
    assert not web_fetch._is_public_http_url("https://example.test/private")


def test_accepts_only_public_dns_results(monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo", lambda *_a, **_k: _addr("93.184.216.34"))
    assert web_fetch._is_public_http_url("https://example.test/page")


def test_fetch_connects_to_validated_ip(monkeypatch):
    resolve_calls = 0

    def _resolve(*_args, **_kwargs):
        nonlocal resolve_calls
        resolve_calls += 1
        return _addr("93.184.216.34" if resolve_calls == 1 else "127.0.0.1")

    class _Response:
        status_code = 200
        headers = {"content-type": "text/html; charset=utf-8"}
        encoding = "utf-8"

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def iter_bytes(self):
            yield b"<title>Example</title><p>This is a sufficiently long public page body for testing.</p>"

    class _Client:
        def __init__(self, **_kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def stream(self, _method, request_url, *, headers, extensions):
            assert request_url == "https://93.184.216.34/page?x=1"
            assert headers["Host"] == "example.test"
            assert extensions["sni_hostname"] == "example.test"
            return _Response()

    monkeypatch.setattr(socket, "getaddrinfo", _resolve)
    monkeypatch.setattr(web_fetch.httpx, "Client", _Client)

    result = web_fetch.fetch_url("https://example.test/page?x=1")

    assert result is not None
    assert result["url"] == "https://example.test/page?x=1"
    assert resolve_calls == 1


def test_redirect_target_is_revalidated(monkeypatch):
    class _Response:
        status_code = 302
        headers = {"location": "http://127.0.0.1/internal"}

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

    class _Client:
        calls = 0

        def __init__(self, **_kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def stream(self, *_args, **_kwargs):
            self.calls += 1
            return _Response()

    monkeypatch.setattr(
        socket,
        "getaddrinfo",
        lambda host, *_a, **_k: _addr("93.184.216.34") if host == "example.test" else _addr("127.0.0.1"),
    )
    monkeypatch.setattr(web_fetch.httpx, "Client", _Client)

    assert web_fetch.fetch_url("https://example.test/start") is None
