"""知识库检索工具：封装 rag.retrieve。"""

from __future__ import annotations

import logging
from typing import Any

from app.rag.retrieve import retrieve

logger = logging.getLogger(__name__)


def search_knowledge(
    *,
    workspace_id: str,
    knowledge_base_ids: list[str],
    query: str,
    top_k: int = 6,
) -> list[dict[str, Any]]:
    """
    检索内部知识库并转为 Researcher 可用的原始条目。

    Returns:
        [{title, url, snippet, sourceType, documentId, chunkId}, ...]
    """
    if not knowledge_base_ids:
        return []
    try:
        hits = retrieve(
            workspace_id=workspace_id,
            knowledge_base_ids=knowledge_base_ids,
            query=query,
            top_k=top_k,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("kb retrieve failed: %s", exc)
        return []

    items: list[dict[str, Any]] = []
    for h in hits:
        content = (h.get("content") or "").strip()
        if not content:
            continue
        doc_id = h.get("documentId") or ""
        chunk_id = h.get("chunkId") or ""
        items.append(
            {
                "title": f"KB chunk {chunk_id[:12]}",
                "url": f"kb://{doc_id}/{chunk_id}",
                "snippet": content[:800],
                "sourceType": "KNOWLEDGE",
                "documentId": doc_id,
                "chunkId": chunk_id,
            }
        )
    return items
