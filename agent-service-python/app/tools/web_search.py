"""联网搜索工具：优先 Tavily，否则返回空以触发 SYNTHETIC 降级。"""

from __future__ import annotations

from typing import Any

import httpx

from app.core.config import get_settings


def search_web(query: str, max_results: int = 5) -> list[dict[str, Any]]:
    """
    执行网页搜索。

    Args:
        query: 搜索查询。
        max_results: 最多返回条数。

    Returns:
        证据候选列表，每项含 title/url/snippet/sourceType。
        未配置 TAVILY_API_KEY 时返回空列表。
    """
    settings = get_settings()
    if not settings.tavily_api_key:
        return []

    payload = {
        "api_key": settings.tavily_api_key,
        "query": query,
        "max_results": max_results,
        "include_answer": False,
    }
    with httpx.Client(timeout=30.0) as client:
        resp = client.post("https://api.tavily.com/search", json=payload)
        resp.raise_for_status()
        data = resp.json()

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
    return results
