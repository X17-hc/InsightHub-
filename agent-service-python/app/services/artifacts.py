"""PostgreSQL metadata access for sandbox artifacts."""
from __future__ import annotations
import json
from typing import Any
import psycopg
from psycopg.rows import dict_row
from app.core.config import get_settings

def _connection():
    s = get_settings()
    return psycopg.connect(host=s.postgres_host, port=s.postgres_port, dbname=s.postgres_db,
                            user=s.postgres_user, password=s.postgres_password,
                            connect_timeout=s.postgres_connect_timeout_seconds, row_factory=dict_row)

def save_all(artifacts: list[dict[str, Any]]) -> None:
    if not artifacts: return
    with _connection() as conn, conn.cursor() as cur:
        for item in artifacts:
            metadata = {"fileName": item["fileName"], "mimeType": item["mimeType"], "size": item["size"]}
            cur.execute("""INSERT INTO analysis_artifact (id, task_id, workspace_id, run_id, artifact_type, title, storage_uri, code_ref, stdout_ref, status, metadata_json)
                VALUES (%(id)s,%(taskId)s,%(workspaceId)s,%(runId)s,%(artifactType)s,%(title)s,%(storageUri)s,%(codeSummary)s,%(stdoutSummary)s,%(status)s,%(metadata)s)""",
                {**item, "metadata": json.dumps(metadata)})

def list_for_task(workspace_id: str, task_id: str) -> list[dict[str, Any]]:
    with _connection() as conn, conn.cursor() as cur:
        cur.execute("""SELECT id, task_id, workspace_id, run_id, artifact_type, title, storage_uri, status, metadata_json, created_at
                       FROM analysis_artifact WHERE workspace_id=%s AND task_id=%s AND status='SUCCESS' ORDER BY created_at DESC""", (workspace_id, task_id))
        return [_public(row) for row in cur.fetchall()]

def get_file_descriptor(workspace_id: str, task_id: str, artifact_id: str) -> tuple[str, str, str] | None:
    """Resolve file details only for the local file-serving path.

    Storage locations are an Agent implementation detail and must not be part
    of metadata returned to Java or the browser.
    """
    with _connection() as conn, conn.cursor() as cur:
        cur.execute("""SELECT storage_uri, metadata_json
                       FROM analysis_artifact WHERE workspace_id=%s AND task_id=%s AND id=%s AND status='SUCCESS'""", (workspace_id, task_id, artifact_id))
        row = cur.fetchone()
        if row is None:
            return None
        metadata = row.get("metadata_json") or {}
        if isinstance(metadata, str):
            metadata = json.loads(metadata)
        return (str(row["storage_uri"]), str(metadata.get("mimeType") or ""), str(metadata.get("fileName") or "artifact"))

def _public(row: dict[str, Any]) -> dict[str, Any]:
    metadata = row.get("metadata_json") or {}
    if isinstance(metadata, str): metadata = json.loads(metadata)
    return {"id": row["id"], "taskId": row["task_id"], "workspaceId": row["workspace_id"], "runId": row["run_id"],
            "artifactType": row["artifact_type"], "title": row["title"], "fileName": metadata.get("fileName"),
            "mimeType": metadata.get("mimeType"), "size": metadata.get("size"), "status": row["status"],
            "createdAt": row["created_at"].isoformat()}
