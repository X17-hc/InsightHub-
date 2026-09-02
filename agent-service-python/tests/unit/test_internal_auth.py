"""内部 API 共享密钥测试。"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app
from app.services.control import CONTROL_PAUSED, InMemoryControlStore, reset_control_store_for_tests


def test_internal_api_rejects_missing_token(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "test-internal-token")
    get_settings.cache_clear()
    with TestClient(app) as client:
        response = client.post("/internal/v1/agent/tasks", json={})
        health = client.get("/health")

    assert response.status_code == 401
    assert health.status_code == 200


def test_internal_api_is_disabled_without_config(monkeypatch):
    monkeypatch.delenv("AGENT_INTERNAL_TOKEN", raising=False)
    get_settings.cache_clear()
    with TestClient(app) as client:
        response = client.post(
            "/internal/v1/agent/tasks",
            headers={"X-Internal-Token": "anything"},
            json={},
        )

    assert response.status_code == 503
    assert response.json()["code"] == "INTERNAL_AUTH_NOT_CONFIGURED"


def test_valid_internal_token_reaches_route_validation(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "test-internal-token")
    get_settings.cache_clear()
    with TestClient(app) as client:
        response = client.post(
            "/internal/v1/agent/tasks",
            headers={"X-Internal-Token": "test-internal-token"},
            json={},
        )

    assert response.status_code == 422


def test_java_can_write_agent_local_control_store(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "test-internal-token")
    get_settings.cache_clear()
    store = InMemoryControlStore()
    reset_control_store_for_tests(store)
    try:
        with TestClient(app) as client:
            response = client.put(
                "/internal/v1/agent/tasks/task-1/control",
                headers={"X-Internal-Token": "test-internal-token"},
                json={"value": "PAUSED", "ttlSeconds": 120},
            )
        assert response.status_code == 200
        assert store.get("task-1") == CONTROL_PAUSED
    finally:
        reset_control_store_for_tests(None)
