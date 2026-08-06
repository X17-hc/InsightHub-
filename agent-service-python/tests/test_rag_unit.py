"""RAG 单元测试（不依赖 PG）：分块 / mock embedding / RRF。"""

from app.rag.chunking import split_fixed
from app.rag.embedding import mock_embed
from app.rag.retrieve import expand_queries, rrf_fuse


def test_split_fixed_overlap():
    text = "abcdefghijklmnopqrstuvwxyz" * 20
    chunks = split_fixed(text, chunk_size=50, overlap=10)
    assert len(chunks) >= 2
    assert chunks[0].index == 0
    assert all(c.content for c in chunks)


def test_mock_embed_deterministic():
    a = mock_embed("hello world", dim=1536)
    b = mock_embed("hello world", dim=1536)
    c = mock_embed("other", dim=1536)
    assert len(a) == 1536
    assert a == b
    assert a != c
    # 近似单位向量
    norm = sum(x * x for x in a) ** 0.5
    assert abs(norm - 1.0) < 1e-5


def test_expand_queries_and_rrf():
    qs = expand_queries("关于 Spring AI 和 LangChain 的对比")
    assert qs[0].startswith("关于")
    list_a = [{"id": "1"}, {"id": "2"}, {"id": "3"}]
    list_b = [{"id": "2"}, {"id": "1"}, {"id": "4"}]
    fused = rrf_fuse([list_a, list_b], top_k=3)
    assert fused[0]["id"] in {"1", "2"}
    assert len(fused) == 3
