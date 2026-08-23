from __future__ import annotations

import json

import pytest

from app.agents import knowledge_researcher, supervisor
from app.agents.critic import _enforce_verdict_invariants
from app.agents.planner import _validate_new_plan
from app.core.config import Settings
from app.schemas.protocol import AgentTaskRequest, CritiqueResult, Plan
from app.services import runner


def _request() -> AgentTaskRequest:
    return AgentTaskRequest.model_validate({
        "taskId": "task-production-guard",
        "workspaceId": "workspace-1",
        "userId": "user-1",
        "query": "research a real market",
    })


def test_production_missing_search_fails_before_graph(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings(app_env="production", agent_mock_llm=False, deepseek_api_key="configured", tavily_api_key="")
    monkeypatch.setattr(runner, "get_settings", lambda: settings)
    result = runner.run_research_task(_request(), trace_id="trace-1")
    assert result.status == "FAILED"
    assert result.error and result.error.code == "SEARCH_NOT_CONFIGURED"
    assert result.report_markdown is None


def test_production_mock_is_rejected_in_stream(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings(app_env="production", agent_mock_llm=True, deepseek_api_key="configured", tavily_api_key="configured")
    monkeypatch.setattr(runner, "get_settings", lambda: settings)
    lines = [json.loads(line) for line in runner.stream_research_task(_request(), trace_id="trace-2")]
    assert lines[-1]["status"] == "FAILED"
    assert lines[-1]["error"]["code"] == "LLM_NOT_CONFIGURED"


def test_development_without_explicit_demo_does_not_fall_back_to_mock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(app_env="development", agent_mock_llm=False,
                        allow_synthetic_demo=False, deepseek_api_key="")
    monkeypatch.setattr(runner, "get_settings", lambda: settings)
    result = runner.run_research_task(_request(), trace_id="trace-3")
    assert result.status == "FAILED"
    assert result.error and result.error.code == "LLM_NOT_CONFIGURED"
    assert result.report_markdown is None


def test_production_readiness_requires_database_secret() -> None:
    settings = Settings(app_env="production", agent_mock_llm=False,
                        deepseek_api_key="configured", tavily_api_key="configured",
                        agent_internal_token="configured", postgres_password="")
    assert "DATABASE_NOT_CONFIGURED" in settings.readiness_errors()


def test_plan_rejects_cycle_and_missing_dependency() -> None:
    with pytest.raises(ValueError, match="cycle"):
        Plan.model_validate({"title": "t", "objective": "o", "tasks": [
            {"id": "a", "type": "web_research", "description": "collect source a", "dependsOn": ["b"]},
            {"id": "b", "type": "web_research", "description": "collect source b", "dependsOn": ["a"]},
        ]})
    with pytest.raises(ValueError, match="missing dependencies"):
        Plan.model_validate({"title": "t", "objective": "o", "tasks": [
            {"id": "a", "type": "web_research", "description": "collect source a", "dependsOn": ["missing"]},
        ]})


def test_new_plan_requires_dimensions_and_concrete_tasks() -> None:
    plan = Plan.model_validate({"title": "t", "objective": "o", "tasks": [
        {"id": "a", "type": "web_research", "description": "collect one authoritative market source", "dependsOn": []},
        {"id": "b", "type": "web_research", "description": "compare named competitors using primary documents", "dependsOn": ["a"]},
    ]})
    with pytest.raises(ValueError, match="research dimensions"):
        _validate_new_plan(plan, has_kb=False)


def test_critic_cannot_pass_with_fewer_than_three_verified_sources() -> None:
    result = _enforce_verdict_invariants(
        CritiqueResult(verdict="PASS", summary="ok"),
        evidence=[{"verified": True}], can_supplement=False, has_kb=False,
        plan={"sourceRequirements": {"minVerifiedSources": 3}},
    )
    assert result.verdict == "FAIL"
    assert "insufficient_verified_sources" in result.gaps


def test_knowledge_result_requires_document_and_chunk_identity(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(knowledge_researcher, "search_knowledge", lambda **_: [
        {"title": "real chunk", "url": "kb://doc/chunk", "snippet": "This is a persisted knowledge chunk with enough content.", "documentId": "doc", "chunkId": "chunk"},
        {"title": "broken", "url": "", "snippet": "This entry lacks a stable database identity.", "documentId": "", "chunkId": ""},
    ])
    result = knowledge_researcher.knowledge_research({
        "task_id": "task-kb", "run_id": "run-1", "workspace_id": "workspace-1",
        "user_query": "query", "knowledge_base_ids": ["kb-1"], "pending_tasks": [],
        "completed_tasks": [], "evidence": [], "events": [], "step_count": 0,
        "max_steps": 10, "deadline_at": 9_999_999_999,
    })
    assert result["evidence"][0]["verificationStatus"] == "VERIFIED"
    assert result["evidence"][1]["verificationStatus"] == "CANDIDATE"


def test_dag_executes_dependency_only_after_predecessor(monkeypatch: pytest.MonkeyPatch) -> None:
    executed: list[str] = []
    def handler(state: dict) -> dict:
        item = state["pending_tasks"][0]
        executed.append(item["id"])
        return {"completed_tasks": state["completed_tasks"] + [{**item, "status": "DONE", "evidenceCount": 1}],
                "evidence": state.get("evidence", []) + [{"id": item["id"]}], "events": [],
                "step_count": state.get("step_count", 0) + 1, "status": "RUNNING"}
    monkeypatch.setattr(supervisor, "web_research", handler)
    result = supervisor.execute_plan({
        "task_id": "task-dag", "run_id": "run-1", "pending_tasks": [
            {"id": "a", "type": "web_research", "description": "first", "dependsOn": []},
            {"id": "b", "type": "web_research", "description": "second", "dependsOn": ["a"]},
        ], "completed_tasks": [], "events": [], "evidence": [], "step_count": 0, "max_parallelism": 3,
    })
    assert executed == ["a", "b"]
    assert result["pending_tasks"] == []


def test_dag_marks_transitive_dependents_skipped_after_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(supervisor, "web_research", lambda state: {
        "status": "FAILED", "errors": [{"code": "SEARCH_UNAVAILABLE", "message": "down"}],
        "events": [], "step_count": 1,
    })
    result = supervisor.execute_plan({
        "task_id": "task-dag-fail", "run_id": "run-1", "pending_tasks": [
            {"id": "a", "type": "web_research", "description": "first", "dependsOn": []},
            {"id": "b", "type": "web_research", "description": "second", "dependsOn": ["a"]},
            {"id": "c", "type": "web_research", "description": "third", "dependsOn": ["b"]},
        ], "completed_tasks": [], "events": [], "step_count": 0, "max_parallelism": 3,
    })
    assert result["status"] == "FAILED"
    assert [item["status"] for item in result["completed_tasks"]] == [
        "FAILED", "SKIPPED_DEPENDENCY_FAILED", "SKIPPED_DEPENDENCY_FAILED"]
    assert [event["type"] for event in result["events"]].count("PLAN_TASK_SKIPPED") == 2
