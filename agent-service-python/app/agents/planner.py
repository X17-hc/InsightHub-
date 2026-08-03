"""Planner Agent：澄清需求并生成结构化研究计划，禁止调用搜索。"""

from __future__ import annotations

import json
import re
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.events import make_event
from app.graph.state import ResearchState

_PLANNER_SYSTEM = """你是 InsightHub 的 Planner Agent。
职责：将用户研究主题拆成结构化计划。
禁止：调用搜索、直接给出最终结论、篡改用户原始需求。
只输出 JSON，不要 Markdown 代码块。格式：
{
  "title": "短标题",
  "objective": "研究目标",
  "tasks": [
    {
      "id": "task-1",
      "type": "web_research",
      "description": "子任务描述",
      "dependsOn": []
    }
  ]
}
要求：至少 1 个、最多 3 个 type=web_research 的任务。
"""


def _extract_json(text: str) -> dict[str, Any]:
    """从模型输出中提取 JSON 对象。"""
    text = text.strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{[\s\S]*\}", text)
        if not match:
            raise
        return json.loads(match.group(0))


def _mock_plan(query: str) -> dict[str, Any]:
    """无 LLM 时的确定性计划。"""
    return {
        "title": f"调研：{query[:40]}",
        "objective": query,
        "tasks": [
            {
                "id": "task-1",
                "type": "web_research",
                "description": f"收集与「{query}」相关的官方资料、功能对比与生态信息",
                "dependsOn": [],
            }
        ],
    }


def create_plan(state: ResearchState) -> dict[str, Any]:
    """
    Planner 节点：生成研究计划并写入状态。

    Returns:
        状态增量（plan / events / step_count 等）。
    """
    settings = get_settings()
    events = list(state.get("events") or [])
    step = int(state.get("step_count") or 0) + 1
    task_id = state["task_id"]
    run_id = state["run_id"]

    started = make_event(
        events=events,
        task_id=task_id,
        run_id=run_id,
        event_type="NODE_STARTED",
        node="create_plan",
        data={"agent": "Planner"},
    )
    events.append(started)

    if settings.agent_mock_llm or not settings.deepseek_api_key:
        plan = _mock_plan(state["user_query"])
    else:
        model = get_chat_model(temperature=0.1)
        resp = model.invoke(
            [
                SystemMessage(content=_PLANNER_SYSTEM),
                HumanMessage(content=state["user_query"]),
            ]
        )
        plan = _extract_json(str(resp.content))

    events.append(
        make_event(
            events=events,
            task_id=task_id,
            run_id=run_id,
            event_type="PLAN_CREATED",
            node="create_plan",
            data={"title": plan.get("title"), "taskCount": len(plan.get("tasks") or [])},
        )
    )
    events.append(
        make_event(
            events=events,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_COMPLETED",
            node="create_plan",
            data={"agent": "Planner"},
        )
    )

    return {
        "plan": plan,
        "clarified_query": plan.get("objective") or state["user_query"],
        "approved": True,  # 第 1 周自动批准
        "step_count": step,
        "events": events[len(state.get("events") or []) :],
    }
