"""Day 1: PLAN 阶段必须在审批节点停止，并严格校验请求协议。"""

import os

import pytest
from pydantic import ValidationError

os.environ["AGENT_MOCK_LLM"] = "true"
os.environ["DEEPSEEK_API_KEY"] = ""

from app.schemas.protocol import AgentTaskRequest
from app.services.runner import run_research_task


def test_plan_phase_stops_before_research() -> None:
    request = AgentTaskRequest.model_validate(
        {
            "taskId": "task-plan-day1",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "调研 Spring Boot 的事务事件机制",
            "phase": "PLAN",
            "planRevision": 1,
            "config": {"requirePlanApproval": True, "enableWebSearch": False},
        }
    )

    result = run_research_task(request, trace_id="trace-plan-day1")

    assert result.status == "WAITING_APPROVAL"
    assert result.plan_revision == 1
    assert result.plan_hash
    assert result.plan is not None
    event_types = {event.type for event in result.events}
    assert {"TASK_STARTED", "PLAN_CREATED", "APPROVAL_REQUIRED"} <= event_types
    assert all(event.node not in {"dispatch_tasks", "knowledge_research", "web_research",
                                   "merge_evidence", "critic_review", "write_report"}
               for event in result.events)


def test_unknown_request_field_is_rejected() -> None:
    with pytest.raises(ValidationError):
        AgentTaskRequest.model_validate(
            {
                "taskId": "task-protocol-day1",
                "workspaceId": "workspace-demo",
                "userId": "user-demo",
                "query": "严格协议校验",
                "unexpectedField": True,
            }
        )
