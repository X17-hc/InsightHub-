"""知识库内部 API：入库 / 检索 / 清理。"""

from __future__ import annotations

import logging
from pathlib import Path
import tempfile
from typing import Annotated

from fastapi import APIRouter, File, Form, HTTPException, UploadFile

from app.core.config import REPO_ROOT, get_settings
from app.rag import pg
from app.rag.ingest import ingest_document
from app.rag.retrieve import retrieve
from app.schemas.knowledge import (
    DeleteKbChunksRequest,
    IngestDocumentRequest,
    IngestDocumentResponse,
    RetrieveRequest,
    RetrieveResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/v1/knowledge", tags=["knowledge-internal"])


def _upload_root() -> Path:
    root = Path(get_settings().upload_root_dir).expanduser()
    if not root.is_absolute():
        root = REPO_ROOT / root
    root = root.resolve(strict=False)
    root.mkdir(parents=True, exist_ok=True)
    return root


def _validated_upload_path(raw_path: str) -> str:
    """限制入库文件位于配置的上传目录，阻止读取任意本机文件。"""
    root = _upload_root()
    candidate = Path(raw_path).expanduser().resolve(strict=True)
    if not candidate.is_file() or not candidate.is_relative_to(root):
        raise ValueError("filePath must be a file under UPLOAD_ROOT_DIR")
    return str(candidate)


@router.post("/documents/ingest-content", response_model=IngestDocumentResponse)
def ingest_content(
    workspace_id: Annotated[str, Form(alias="workspaceId")],
    knowledge_base_id: Annotated[str, Form(alias="knowledgeBaseId")],
    document_id: Annotated[str, Form(alias="documentId")],
    file: UploadFile = File(...),
    content_type: Annotated[str | None, Form(alias="contentType")] = None,
    file_name: Annotated[str | None, Form(alias="fileName")] = None,
) -> IngestDocumentResponse:
    """接收 Java 上传的文件流并入库，不依赖跨主机绝对路径。"""
    settings = get_settings()
    suffix = Path(file_name or file.filename or "document.bin").suffix[:16]
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", suffix=suffix, prefix="ingest-", dir=_upload_root(), delete=False
        ) as target:
            temp_path = Path(target.name)
            total = 0
            while chunk := file.file.read(64 * 1024):
                total += len(chunk)
                if total > settings.knowledge_upload_max_bytes:
                    raise ValueError("document exceeds upload size limit")
                target.write(chunk)
        result = ingest_document(
            workspace_id=workspace_id,
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
            file_path=str(temp_path),
            content_type=content_type,
            file_name=file_name or file.filename,
        )
        return IngestDocumentResponse(
            documentId=result["documentId"],
            chunkCount=result["chunkCount"],
            embeddingModel=result["embeddingModel"],
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="document content is invalid") from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("stream ingest failed documentId=%s errorType=%s", document_id, type(exc).__name__)
        raise HTTPException(status_code=500, detail="knowledge ingest failed") from exc
    finally:
        try:
            file.file.close()
        finally:
            if temp_path is not None:
                temp_path.unlink(missing_ok=True)


@router.post("/documents/ingest", response_model=IngestDocumentResponse)
def ingest(req: IngestDocumentRequest) -> IngestDocumentResponse:
    """解析本地文件并写入 PGVector（按 documentId 幂等）。"""
    try:
        result = ingest_document(
            workspace_id=req.workspace_id,
            knowledge_base_id=req.knowledge_base_id,
            document_id=req.document_id,
            file_path=_validated_upload_path(req.file_path),
            content_type=req.content_type,
            file_name=req.file_name,
        )
        return IngestDocumentResponse(
            documentId=result["documentId"],
            chunkCount=result["chunkCount"],
            embeddingModel=result["embeddingModel"],
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="document file was not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="document path or content is invalid") from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("ingest failed errorType=%s", type(exc).__name__)
        raise HTTPException(status_code=500, detail="knowledge ingest failed") from exc


@router.post("/retrieve", response_model=RetrieveResponse)
def retrieve_api(req: RetrieveRequest) -> RetrieveResponse:
    """混合检索（强制 workspace + KB 过滤）。"""
    try:
        hits = retrieve(
            workspace_id=req.workspace_id,
            knowledge_base_ids=req.knowledge_base_ids,
            query=req.query,
            top_k=req.top_k,
        )
        return RetrieveResponse(hits=hits)
    except Exception as exc:  # noqa: BLE001
        logger.exception("retrieve failed errorType=%s", type(exc).__name__)
        raise HTTPException(status_code=500, detail="knowledge retrieval failed") from exc


@router.post("/chunks/delete-by-kb")
def delete_kb_chunks(req: DeleteKbChunksRequest) -> dict:
    """删除指定 KB 下全部向量片段。"""
    n = pg.delete_chunks_by_knowledge_base(req.workspace_id, req.knowledge_base_id)
    return {"deleted": n}
