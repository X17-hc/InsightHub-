"""Supervisor mock 路径下的 handoff 顺序。"""

import os

os.environ["AGENT_MOCK_LLM"] = "true"
os.environ["DEEPSEEK_API_KEY"] = ""
os.environ["CHECKPOINT_BACKEND"] = "memory"

from app.graph.builder import reset_graph_for_tests
from app.schemas.protocol import AgentTaskRequest, Plan
from app.services.runner import run_research_task


def test_supervisor_handoff_planner_research_critic_writer() -> None:
    reset_graph_for_tests()
    request = AgentTaskRequest.model_validate(
        {
            "taskId": "task-handoff-order",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "验证 Supervisor handoff 顺序",
            "config": {"maxSteps": 20, "requirePlanApproval": False, "enableWebSearch": False},
        }
    )
    result = run_research_task(request, trace_id="trace-handoff")
    assert result.status == "COMPLETED"
    hops = [event.data for event in result.events if event.type == "AGENT_HANDOFF"]
    targets = [item.get("to") for item in hops]
    assert "create_plan" in targets
    assert any(name in targets for name in ("web_research", "knowledge_research"))
    assert "merge_evidence" in targets
    reset_graph_for_tests()


def test_sequential_web_tasks_complete_without_handoff_cycle(monkeypatch) -> None:
    """复现线上失败：两条有依赖的网络研究连续 handoff 不应被当成环。"""

    def two_web_plan(query: str, has_kb: bool) -> Plan:
        del has_kb
        return Plan.model_validate(
            {
                "title": f"调研：{query[:40]}",
                "objective": query,
                "tasks": [
                    {"id": "task-1", "type": "web_research", "description": "市场规模", "dependsOn": []},
                    {"id": "task-2", "type": "web_research", "description": "竞争格局", "dependsOn": ["task-1"]},
                ],
            }
        )

    monkeypatch.setattr("app.agents.planner._mock_plan", two_web_plan)
    request = AgentTaskRequest.model_validate(
        {
            "taskId": "task-sequential-web",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "分析企业级 AI 知识库市场",
            "config": {"maxSteps": 20, "requirePlanApproval": False, "enableWebSearch": False},
        }
    )
    result = run_research_task(request, trace_id="trace-sequential-web")
    assert result.status == "COMPLETED"
    assert result.error is None
    web_ids = [
        event.data.get("planTaskId")
        for event in result.events
        if event.type == "AGENT_HANDOFF" and event.data.get("to") == "web_research"
    ]
    assert web_ids[:2] == ["task-1", "task-2"]
    assert len(web_ids) >= 2
