"""Embedding：MOCK 确定性伪向量 或 OpenAI 兼容 API。"""

from __future__ import annotations

import hashlib
import logging
import struct
from typing import Sequence

import httpx

from app.core.config import get_settings

logger = logging.getLogger(__name__)


def _use_mock() -> bool:
    """是否使用确定性伪向量。"""
    settings = get_settings()
    return bool(settings.embedding_mock or settings.agent_mock_llm)


def mock_embed(text: str, dim: int = 1536) -> list[float]:
    """
    由文本 SHA256 扩展生成确定性单位向量（验收 / 无 Key）。

    Args:
        text: 输入文本。
        dim: 维度，默认 1536。

    Returns:
        长度为 dim 的 float 列表。
    """
    seed = hashlib.sha256(text.encode("utf-8")).digest()
    values: list[float] = []
    counter = 0
    while len(values) < dim:
        block = hashlib.sha256(seed + counter.to_bytes(4, "big")).digest()
        for i in range(0, len(block), 4):
            if len(values) >= dim:
                break
            # 映射到 [-1, 1]
            (u,) = struct.unpack(">I", block[i : i + 4])
            values.append((u / 0xFFFFFFFF) * 2.0 - 1.0)
        counter += 1
    # L2 归一化，便于余弦距离
    norm = sum(v * v for v in values) ** 0.5 or 1.0
    return [v / norm for v in values]


def embed_texts(texts: Sequence[str]) -> list[list[float]]:
    """
    批量 Embedding。

    MOCK 或无 API Key 时走确定性伪向量；否则调用 OpenAI 兼容接口。
    """
    settings = get_settings()
    dim = int(settings.embedding_dim)
    cleaned = [t if t is not None else "" for t in texts]
    if not cleaned:
        return []

    if _use_mock() or not settings.embedding_api_key:
        return [mock_embed(t, dim) for t in cleaned]

    url = settings.embedding_base_url.rstrip("/") + "/embeddings"
    headers = {
        "Authorization": f"Bearer {settings.embedding_api_key}",
        "Content-Type": "application/json",
    }
    payload = {"model": settings.embedding_model, "input": cleaned}
    try:
        with httpx.Client(timeout=60.0) as client:
            resp = client.post(url, headers=headers, json=payload)
            resp.raise_for_status()
            data = resp.json()
        items = sorted(data.get("data") or [], key=lambda x: int(x.get("index", 0)))
        vectors = [list(item["embedding"]) for item in items]
        if len(vectors) != len(cleaned):
            raise RuntimeError(f"embedding count mismatch: {len(vectors)} != {len(cleaned)}")
        return vectors
    except Exception as exc:  # noqa: BLE001
        logger.exception("embedding API failed")
        raise RuntimeError("embedding API failed") from exc


def embed_query(text: str) -> list[float]:
    """单条查询向量。"""
    return embed_texts([text])[0]
