from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

import app.api.knowledge as knowledge_api
from app.core.config import get_settings
from app.main import app


def test_ingest_content_transfers_bytes_without_accepting_host_path(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "test-internal-token")
    get_settings.cache_clear()
    settings = get_settings()
    settings.upload_root_dir = str(tmp_path)
    captured: dict[str, object] = {}

    def fake_ingest_document(**kwargs):
        path = Path(str(kwargs["file_path"]))
        captured["content"] = path.read_bytes()
        captured["path"] = path
        return {"documentId": kwargs["document_id"], "chunkCount": 2, "embeddingModel": "test"}

    monkeypatch.setattr(knowledge_api, "ingest_document", fake_ingest_document)
    with TestClient(app) as client:
        response = client.post(
            "/internal/v1/knowledge/documents/ingest-content",
            headers={"X-Internal-Token": "test-internal-token"},
            data={
                "workspaceId": "workspace-1",
                "knowledgeBaseId": "kb-1",
                "documentId": "doc-1",
                "contentType": "text/plain",
                "fileName": "notes.txt",
            },
            files={"file": ("notes.txt", b"real document bytes", "text/plain")},
        )

    assert response.status_code == 200
    assert response.json()["chunkCount"] == 2
    assert captured["content"] == b"real document bytes"
    assert not Path(str(captured["path"])).exists()
