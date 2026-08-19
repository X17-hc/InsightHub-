"""图路由与 mock 执行冒烟测试。"""

import os

os.environ["AGENT_MOCK_LLM"] = "true"
os.environ["DEEPSEEK_API_KEY"] = ""

from app.schemas.protocol import AgentTaskRequest
from app.services.runner import run_research_task


def test_three_agent_pipeline_produces_markdown():
    req = AgentTaskRequest.model_validate(
        {
            "taskId": "task-test-001",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "比较 Spring AI 和 LangChain4j 的多 Agent 能力",
            "config": {"maxSteps": 20, "requirePlanApproval": False, "enableWebSearch": False},
        }
    )
    result = run_research_task(req, trace_id="trace-test")
    assert result.status == "COMPLETED"
    assert result.report_markdown
    assert result.report_markdown.lstrip().startswith("#")
    types = {e.type for e in result.events}
    assert "PLAN_CREATED" in types
    assert "NODE_COMPLETED" in types
    assert "TASK_COMPLETED" in types


def test_max_steps_stops_before_research_and_writer():
    req = AgentTaskRequest.model_validate(
        {
            "taskId": "task-max-steps",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "验证最大步骤限制",
            "config": {"maxSteps": 1, "enableWebSearch": False},
        }
    )

    result = run_research_task(req, trace_id="trace-max-steps")

    assert result.status == "FAILED"
    assert result.error is not None
    assert result.error.code == "MAX_STEPS_EXCEEDED"
    assert result.report_markdown is None
    assert all(event.node not in {"web_research", "merge_evidence", "critic_review", "write_report"}
               for event in result.events)


def test_max_steps_is_enforced_after_supervisor():
    req = AgentTaskRequest.model_validate(
        {
            "taskId": "task-max-steps-after-supervisor",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "验证后续节点步骤限制",
            "config": {"maxSteps": 2, "enableWebSearch": False},
        }
    )

    result = run_research_task(req, trace_id="trace-max-steps-2")

    assert result.status == "FAILED"
    assert result.error is not None
    assert result.error.code == "MAX_STEPS_EXCEEDED"
    assert result.report_markdown is None
    assert any(event.node == "knowledge_research" for event in result.events)
    assert all(event.node not in {"web_research", "merge_evidence", "critic_review", "write_report"}
               for event in result.events)
