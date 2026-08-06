"""混合检索：查询扩展 + 向量/关键词 + RRF 重排。"""

from __future__ import annotations

import logging
import re
from typing import Any

from app.core.config import get_settings
from app.rag.embedding import embed_query
from app.rag import pg

logger = logging.getLogger(__name__)


def expand_queries(query: str) -> list[str]:
    """
    查询扩展：MOCK 下生成去停用词变体；非 MOCK 可后续接 LLM。

    Returns:
        去重后的查询列表（至少含原查询）。
    """
    q = (query or "").strip()
    if not q:
        return []
    variants = [q]
    # 简单去常见虚词，形成第二条检索串
    stop = {"的", "了", "和", "与", "及", "在", "是", "对", "关于", "如何", "什么", "哪些", "a", "the", "of", "and", "to"}
    tokens = [t for t in re.split(r"\s+|[,，。？?！!；;：:]+", q) if t and t.lower() not in stop]
    if tokens:
        alt = " ".join(tokens)
        if alt != q:
            variants.append(alt)
    # 去重保序
    seen: set[str] = set()
    out: list[str] = []
    for v in variants:
        if v not in seen:
            seen.add(v)
            out.append(v)
    return out


def rrf_fuse(
    ranked_lists: list[list[dict[str, Any]]],
    *,
    k: int = 60,
    top_k: int = 8,
) -> list[dict[str, Any]]:
    """
    Reciprocal Rank Fusion。

    Args:
        ranked_lists: 多个有序列表，元素需含 id。
        k: RRF 常数。
        top_k: 返回条数。
    """
    scores: dict[str, float] = {}
    best: dict[str, dict[str, Any]] = {}
    for lst in ranked_lists:
        for rank, item in enumerate(lst, start=1):
            cid = str(item.get("id") or "")
            if not cid:
                continue
            scores[cid] = scores.get(cid, 0.0) + 1.0 / (k + rank)
            if cid not in best:
                best[cid] = item
    ordered = sorted(scores.items(), key=lambda x: x[1], reverse=True)[:top_k]
    results: list[dict[str, Any]] = []
    for cid, score in ordered:
        row = dict(best[cid])
        row["rrfScore"] = score
        results.append(row)
    return results


def _to_hit(row: dict[str, Any], score: float) -> dict[str, Any]:
    """统一检索命中结构。"""
    return {
        "chunkId": row.get("id"),
        "documentId": row.get("document_id"),
        "knowledgeBaseId": row.get("knowledge_base_id"),
        "workspaceId": row.get("workspace_id"),
        "chunkIndex": row.get("chunk_index"),
        "content": row.get("content") or "",
        "pageNo": row.get("page_no"),
        "locStart": row.get("loc_start"),
        "locEnd": row.get("loc_end"),
        "score": score,
        "sourceType": "KNOWLEDGE",
    }


def retrieve(
    *,
    workspace_id: str,
    knowledge_base_ids: list[str],
    query: str,
    top_k: int = 8,
) -> list[dict[str, Any]]:
    """
    混合检索入口。

    强制 workspace_id + knowledge_base_ids 过滤；空 KB 列表返回 []。
    """
    if not workspace_id or not knowledge_base_ids:
        return []
    queries = expand_queries(query)
    if not queries:
        return []

    settings = get_settings()
    fetch_k = max(top_k * 2, 8)
    ranked: list[list[dict[str, Any]]] = []

    for q in queries:
        try:
            vec = embed_query(q)
            vrows = pg.vector_search(
                workspace_id=workspace_id,
                knowledge_base_ids=knowledge_base_ids,
                query_embedding=vec,
                top_k=fetch_k,
            )
            ranked.append(vrows)
        except Exception as exc:  # noqa: BLE001
            logger.warning("vector search failed: %s", exc)
        try:
            krows = pg.keyword_search(
                workspace_id=workspace_id,
                knowledge_base_ids=knowledge_base_ids,
                query=q,
                top_k=fetch_k,
            )
            ranked.append(krows)
        except Exception as exc:  # noqa: BLE001
            logger.warning("keyword search failed: %s", exc)

    fused = rrf_fuse(ranked, top_k=top_k)
    return [_to_hit(r, float(r.get("rrfScore") or 0.0)) for r in fused]
