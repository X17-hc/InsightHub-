"""文档入库：解析 → 分块 → Embedding → PGVector upsert。"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from app.core.config import get_settings
from app.rag.chunking import split_fixed
from app.rag.embedding import embed_texts
from app.rag.parse import parse_file
from app.rag import pg

logger = logging.getLogger(__name__)


def ingest_document(
    *,
    workspace_id: str,
    knowledge_base_id: str,
    document_id: str,
    file_path: str,
    content_type: str | None = None,
    file_name: str | None = None,
) -> dict[str, Any]:
    """
    幂等入库：先删同 document_id 旧片段，再写入新片段。

    Returns:
        {documentId, chunkCount, embeddingModel}
    """
    settings = get_settings()
    text = parse_file(file_path, content_type)
    chunks = split_fixed(text, chunk_size=settings.chunk_size, overlap=settings.chunk_overlap)
    if not chunks:
        raise ValueError("no text chunks after parsing")

    vectors = embed_texts([c.content for c in chunks])
    model_name = "mock-sha256" if (settings.embedding_mock or settings.agent_mock_llm or not settings.embedding_api_key) else settings.embedding_model

    deleted = pg.delete_chunks_by_document(document_id)
    if deleted:
        logger.info("deleted %s old chunks for document %s", deleted, document_id)

    rows: list[dict[str, Any]] = []
    for ch, vec in zip(chunks, vectors, strict=True):
        rows.append(
            {
                "id": f"chk-{uuid.uuid4().hex[:16]}",
                "workspace_id": workspace_id,
                "knowledge_base_id": knowledge_base_id,
                "document_id": document_id,
                "chunk_index": ch.index,
                "parent_chunk_id": None,
                "content": ch.content,
                "content_tokens": max(1, len(ch.content) // 2),
                "metadata_json": {
                    "fileName": file_name,
                    "locStart": ch.loc_start,
                    "locEnd": ch.loc_end,
                },
                "embedding": vec,
                "embedding_model": model_name,
                "page_no": None,
                "loc_start": ch.loc_start,
                "loc_end": ch.loc_end,
            }
        )
    pg.insert_chunks(rows)
    return {
        "documentId": document_id,
        "chunkCount": len(rows),
        "embeddingModel": model_name,
    }
