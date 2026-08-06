"""知识库内部协议模型（Java ↔ Python）。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class IngestDocumentRequest(BaseModel):
    """文档入库请求。"""

    workspace_id: str = Field(alias="workspaceId")
    knowledge_base_id: str = Field(alias="knowledgeBaseId")
    document_id: str = Field(alias="documentId")
    file_path: str = Field(alias="filePath")
    content_type: str | None = Field(default=None, alias="contentType")
    file_name: str | None = Field(default=None, alias="fileName")

    model_config = {"populate_by_name": True}


class IngestDocumentResponse(BaseModel):
    """入库结果。"""

    document_id: str = Field(alias="documentId")
    chunk_count: int = Field(alias="chunkCount")
    embedding_model: str = Field(alias="embeddingModel")

    model_config = {"populate_by_name": True}


class RetrieveRequest(BaseModel):
    """混合检索请求。"""

    workspace_id: str = Field(alias="workspaceId")
    knowledge_base_ids: list[str] = Field(default_factory=list, alias="knowledgeBaseIds")
    query: str
    top_k: int = Field(default=8, alias="topK")

    model_config = {"populate_by_name": True}


class RetrieveHit(BaseModel):
    """单条检索命中。"""

    chunk_id: str = Field(alias="chunkId")
    document_id: str = Field(alias="documentId")
    knowledge_base_id: str = Field(alias="knowledgeBaseId")
    content: str
    score: float = 0.0
    source_type: str = Field(default="KNOWLEDGE", alias="sourceType")
    page_no: int | None = Field(default=None, alias="pageNo")

    model_config = {"populate_by_name": True}


class RetrieveResponse(BaseModel):
    """检索响应。"""

    hits: list[dict] = Field(default_factory=list)

    model_config = {"populate_by_name": True}


class DeleteKbChunksRequest(BaseModel):
    """删除 KB 下全部片段。"""

    workspace_id: str = Field(alias="workspaceId")
    knowledge_base_id: str = Field(alias="knowledgeBaseId")

    model_config = {"populate_by_name": True}
