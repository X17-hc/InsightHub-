"""联网搜索工具：通过 Tavily 发现候选来源；正式环境失败关闭。"""

from __future__ import annotations

from typing import Any

import httpx
import time

from app.core.config import get_settings


class WebSearchError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def search_web(
    query: str,
    max_results: int = 5,
    timeout_seconds: float = 30.0,
) -> list[dict[str, Any]]:
    """
    执行网页搜索。

    Args:
        query: 搜索查询。
        max_results: 最多返回条数。

    Returns:
        证据候选列表，每项含 title/url/snippet/sourceType。

    Raises:
        WebSearchError: 未配置、远端不可用或未返回结果。
    """
    settings = get_settings()
    if not settings.tavily_api_key:
        raise WebSearchError("SEARCH_NOT_CONFIGURED", "real web search is not configured")

    payload = {
        "api_key": settings.tavily_api_key,
        "query": query,
        "max_results": max_results,
        "include_answer": False,
    }
    if timeout_seconds <= 0:
        raise TimeoutError("agent task timed out")
    data: dict[str, Any] = {}
    deadline = time.monotonic() + timeout_seconds
    for attempt in range(2):
        try:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise WebSearchError("SEARCH_UNAVAILABLE", "web search timed out")
            with httpx.Client(timeout=remaining) as client:
                resp = client.post("https://api.tavily.com/search", json=payload)
            if resp.status_code in {429} or resp.status_code >= 500:
                if attempt == 0:
                    time.sleep(0.2)
                    continue
                raise WebSearchError("SEARCH_UNAVAILABLE", "web search is temporarily unavailable")
            if resp.status_code >= 400:
                raise WebSearchError("SEARCH_UNAVAILABLE", f"web search rejected the request ({resp.status_code})")
            data = resp.json()
            break
        except WebSearchError:
            raise
        except (httpx.TimeoutException, httpx.NetworkError) as exc:
            if attempt == 0:
                time.sleep(0.2)
                continue
            raise WebSearchError("SEARCH_UNAVAILABLE", "web search request failed") from exc

    results: list[dict[str, Any]] = []
    for item in data.get("results", [])[:max_results]:
        results.append(
            {
                "title": item.get("title") or "Untitled",
                "url": item.get("url") or "",
                "snippet": item.get("content") or "",
                "sourceType": "WEB",
            }
        )
    if not results:
        raise WebSearchError("SEARCH_NO_RESULTS", "web search returned no usable results")
    return results
