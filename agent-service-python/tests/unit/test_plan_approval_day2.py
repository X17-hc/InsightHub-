"""Day 2: 同一 taskId:runId 的计划确认恢复。"""

import json
import os

os.environ["AGENT_MOCK_LLM"] = "true"
os.environ["DEEPSEEK_API_KEY"] = ""

from app.schemas.protocol import AgentTaskRequest
from app.services.runner import approve_plan_research_task, stream_research_task


def _plan_request() -> AgentTaskRequest:
    return AgentTaskRequest.model_validate({
        "taskId": "task-day2-approval", "workspaceId": "workspace-demo", "userId": "user-demo",
        "query": "验证审批后从同一检查点继续", "runId": "run-day2-001", "phase": "PLAN",
        "planRevision": 1, "config": {"requirePlanApproval": True, "enableWebSearch": False},
    })


def test_matching_plan_hash_resumes_same_run_checkpoint() -> None:
    lines = [json.loads(line) for line in stream_research_task(_plan_request(), trace_id="trace-day2")]
    waiting = next(line for line in lines if line.get("type") == "TASK_RESULT")
    assert waiting["status"] == "WAITING_APPROVAL"
    resumed = [json.loads(line) for line in approve_plan_research_task(
        "task-day2-approval", run_id="run-day2-001", approved_plan_hash=waiting["planHash"], trace_id="trace-day2")]
    assert resumed[-1]["type"] == "TASK_RESULT"
    assert resumed[-1]["status"] == "COMPLETED"


def test_wrong_plan_hash_never_resumes_graph() -> None:
    lines = [json.loads(line) for line in stream_research_task(_plan_request(), trace_id="trace-day2")]
    assert any(line.get("status") == "WAITING_APPROVAL" for line in lines)
    result = [json.loads(line) for line in approve_plan_research_task(
        "task-day2-approval", run_id="run-day2-001", approved_plan_hash="0" * 64)]
    assert result[-1]["status"] == "FAILED"
    assert result[-1]["error"]["code"] == "PLAN_HASH_MISMATCH"
