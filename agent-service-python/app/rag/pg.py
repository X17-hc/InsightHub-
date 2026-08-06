"""PostgreSQL / PGVector 连接与 document_chunk CRUD。"""

from __future__ import annotations

import json
import logging
from contextlib import contextmanager
from typing import Any, Iterator, Sequence

import psycopg
from psycopg.rows import dict_row

from app.core.config import get_settings

logger = logging.getLogger(__name__)


def _dsn() -> str:
    """构造 libpq DSN。"""
    s = get_settings()
    return (
        f"host={s.postgres_host} port={s.postgres_port} dbname={s.postgres_db} "
        f"user={s.postgres_user} password={s.postgres_password}"
    )


@contextmanager
def pg_conn() -> Iterator[psycopg.Connection]:
    """获取短连接（自动 commit/rollback）。"""
    conn = psycopg.connect(_dsn(), row_factory=dict_row)
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def _vector_literal(vec: Sequence[float]) -> str:
    """转为 pgvector 文本字面量 '[a,b,...]'。"""
    return "[" + ",".join(f"{float(x):.8f}" for x in vec) + "]"


def delete_chunks_by_document(document_id: str) -> int:
    """按 document_id 删除全部片段（入库幂等前置）。"""
    with pg_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM document_chunk WHERE document_id = %s", (document_id,))
            return cur.rowcount or 0


def insert_chunks(rows: list[dict[str, Any]]) -> int:
    """
    批量插入 document_chunk。

    每行需含：id, workspace_id, knowledge_base_id, document_id, chunk_index,
    content, content_tokens, metadata_json, embedding(list[float]), embedding_model,
    page_no, loc_start, loc_end
    """
    if not rows:
        return 0
    sql = """
        INSERT INTO document_chunk (
          id, workspace_id, knowledge_base_id, document_id, chunk_index,
          parent_chunk_id, content, content_tokens, metadata_json,
          embedding, embedding_model, page_no, loc_start, loc_end
        ) VALUES (
          %(id)s, %(workspace_id)s, %(knowledge_base_id)s, %(document_id)s, %(chunk_index)s,
          %(parent_chunk_id)s, %(content)s, %(content_tokens)s, %(metadata_json)s::jsonb,
          %(embedding)s::vector, %(embedding_model)s, %(page_no)s, %(loc_start)s, %(loc_end)s
        )
    """
    with pg_conn() as conn:
        with conn.cursor() as cur:
            for row in rows:
                payload = dict(row)
                payload["embedding"] = _vector_literal(row["embedding"])
                meta = row.get("metadata_json")
                payload["metadata_json"] = json.dumps(meta or {}, ensure_ascii=False)
                cur.execute(sql, payload)
            return len(rows)


def vector_search(
    *,
    workspace_id: str,
    knowledge_base_ids: list[str],
    query_embedding: Sequence[float],
    top_k: int = 8,
) -> list[dict[str, Any]]:
    """余弦距离向量检索（强制租户 + KB 过滤）。"""
    if not knowledge_base_ids:
        return []
    lit = _vector_literal(query_embedding)
    sql = """
        SELECT id, workspace_id, knowledge_base_id, document_id, chunk_index,
               content, page_no, loc_start, loc_end, metadata_json,
               (embedding <=> %s::vector) AS distance
        FROM document_chunk
        WHERE workspace_id = %s
          AND knowledge_base_id = ANY(%s)
          AND embedding IS NOT NULL
        ORDER BY embedding <=> %s::vector
        LIMIT %s
    """
    with pg_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, (lit, workspace_id, knowledge_base_ids, lit, top_k))
            return list(cur.fetchall())


def keyword_search(
    *,
    workspace_id: str,
    knowledge_base_ids: list[str],
    query: str,
    top_k: int = 8,
) -> list[dict[str, Any]]:
    """基于 content_tsv 的关键词检索。"""
    if not knowledge_base_ids or not (query or "").strip():
        return []
    sql = """
        SELECT id, workspace_id, knowledge_base_id, document_id, chunk_index,
               content, page_no, loc_start, loc_end, metadata_json,
               ts_rank(content_tsv, plainto_tsquery('simple', %s)) AS rank
        FROM document_chunk
        WHERE workspace_id = %s
          AND knowledge_base_id = ANY(%s)
          AND content_tsv @@ plainto_tsquery('simple', %s)
        ORDER BY rank DESC
        LIMIT %s
    """
    q = query.strip()
    with pg_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, (q, workspace_id, knowledge_base_ids, q, top_k))
            return list(cur.fetchall())


def delete_chunks_by_knowledge_base(workspace_id: str, knowledge_base_id: str) -> int:
    """删除某 KB 下全部片段（禁用 KB 时清理）。"""
    with pg_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "DELETE FROM document_chunk WHERE workspace_id = %s AND knowledge_base_id = %s",
                (workspace_id, knowledge_base_id),
            )
            return cur.rowcount or 0
