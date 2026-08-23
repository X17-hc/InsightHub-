"""Contract tests for artifact metadata visibility."""

from __future__ import annotations

from datetime import datetime, timezone

from app.services.artifacts import _public


def test_public_artifact_metadata_never_contains_storage_uri() -> None:
    row = {
        "id": "artifact-1",
        "task_id": "task-1",
        "workspace_id": "workspace-1",
        "run_id": "run-1",
        "artifact_type": "CHART",
        "title": "summary",
        "storage_uri": "artifact://task-1/private/result.png",
        "status": "SUCCESS",
        "metadata_json": {"fileName": "result.png", "mimeType": "image/png", "size": 12},
        "created_at": datetime.now(timezone.utc),
    }

    result = _public(row)

    assert result["fileName"] == "result.png"
    assert "storageUri" not in result
    assert "private" not in str(result)
