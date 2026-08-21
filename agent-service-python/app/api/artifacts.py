"""Internal artifact metadata and content APIs; Java is the public authorization boundary."""
from __future__ import annotations
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse
from app.services.artifacts import get_one, list_for_task
from app.services.analysis_sandbox import resolve_artifact

router = APIRouter(prefix="/internal/v1/agent/tasks", tags=["artifact-internal"])

@router.get("/{task_id}/artifacts")
def list_artifacts(task_id: str, workspaceId: str = Query(min_length=1)) -> list[dict]:
    return list_for_task(workspaceId, task_id)

@router.get("/{task_id}/artifacts/{artifact_id}/content")
def artifact_content(task_id: str, artifact_id: str, workspaceId: str = Query(min_length=1)) -> FileResponse:
    artifact = get_one(workspaceId, task_id, artifact_id)
    if artifact is None: raise HTTPException(status_code=404, detail="artifact not found")
    try: path = resolve_artifact(str(artifact["storageUri"]))
    except FileNotFoundError as exc: raise HTTPException(status_code=404, detail="artifact file not found") from exc
    if not path.is_file(): raise HTTPException(status_code=404, detail="artifact file not found")
    return FileResponse(path, media_type=str(artifact["mimeType"]), filename=str(artifact["fileName"]))
