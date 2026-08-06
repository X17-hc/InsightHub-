"""文档分块：FIXED 滑动窗口。"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TextChunk:
    """单个文本片段。"""

    index: int
    content: str
    loc_start: int
    loc_end: int


def split_fixed(text: str, *, chunk_size: int = 500, overlap: int = 80) -> list[TextChunk]:
    """
    按字符滑动窗口分块。

    Args:
        text: 原文。
        chunk_size: 窗口大小（字符）。
        overlap: 相邻块重叠字符数。

    Returns:
        非空片段列表；空文本返回 []。
    """
    cleaned = (text or "").replace("\r\n", "\n").strip()
    if not cleaned:
        return []
    if chunk_size <= 0:
        raise ValueError("chunk_size must be > 0")
    if overlap < 0 or overlap >= chunk_size:
        raise ValueError("overlap must be in [0, chunk_size)")

    chunks: list[TextChunk] = []
    start = 0
    idx = 0
    n = len(cleaned)
    while start < n:
        end = min(start + chunk_size, n)
        piece = cleaned[start:end].strip()
        if piece:
            chunks.append(TextChunk(index=idx, content=piece, loc_start=start, loc_end=end))
            idx += 1
        if end >= n:
            break
        start = end - overlap
    return chunks
