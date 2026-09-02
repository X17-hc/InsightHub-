"""Internal artifact metadata and content APIs; Java is the public authorization boundary."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse

from app.services.artifacts import get_file_descriptor, list_for_task
from app.services.analysis_sandbox import resolve_artifact

router = APIRouter(prefix="/internal/v1/agent/tasks", tags=["artifact-internal"])


@router.get("/{task_id}/artifacts")
def list_artifacts(task_id: str, workspaceId: str = Query(min_length=1)) -> list[dict]:
    return list_for_task(workspaceId, task_id)

def _artifact_response(task_id: str, artifact_id: str, workspace_id: str) -> FileResponse:
    """Build the response after ownership has been checked in PostgreSQL."""
    descriptor = get_file_descriptor(workspace_id, task_id, artifact_id)
    if descriptor is None:
        raise HTTPException(status_code=404, detail="artifact not found")
    storage_uri, mime_type, file_name = descriptor
    try:
        path = resolve_artifact(storage_uri)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="artifact file not found") from exc
    if not path.is_file():
        raise HTTPException(status_code=404, detail="artifact file not found")
    return FileResponse(path, media_type=mime_type, filename=file_name)


@router.get("/{task_id}/artifacts/{artifact_id}/content")
def artifact_content(task_id: str, artifact_id: str, workspaceId: str = Query(min_length=1)) -> FileResponse:
    return _artifact_response(task_id, artifact_id, workspaceId)


@router.head("/{task_id}/artifacts/{artifact_id}/content")
def artifact_content_head(task_id: str, artifact_id: str, workspaceId: str = Query(min_length=1)) -> FileResponse:
    return _artifact_response(task_id, artifact_id, workspaceId)
