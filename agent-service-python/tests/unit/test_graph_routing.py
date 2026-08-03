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
