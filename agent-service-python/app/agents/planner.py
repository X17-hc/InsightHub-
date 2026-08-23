"""Planner Agent：澄清需求并生成结构化研究计划，禁止调用搜索。"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.events import make_event
from app.graph.deadline import remaining_seconds
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.schemas.protocol import Plan

_PLANNER_SYSTEM = """你是 InsightHub Planner。只输出 JSON，不调用工具，不输出结论。
格式：{"title":"...","objective":"...","tasks":[
{"id":"task-1","type":"web_research|knowledge_research","description":"...","dependsOn":[]}
],"researchDimensions":["..."],"sourceRequirements":{"minVerifiedSources":3,"requireOfficialSources":true}}
新计划至少 2 个、最多 8 个任务；复杂主题必须拆分为独立取证维度；dependsOn 必须构成无环图；绑定知识库时至少包含一个 knowledge_research。"""


def _extract_json(text: str) -> dict[str, Any]:
    """从模型输出中提取 JSON 对象。"""
    try:
        return json.loads(text.strip())
    except json.JSONDecodeError:
        match = re.search(r"\{[\s\S]*\}", text)
        if not match:
            raise
        return json.loads(match.group(0))


def _mock_plan(query: str, has_kb: bool) -> Plan:
    """无 LLM 时的确定性计划。"""
    tasks = []
    if has_kb:
        tasks.append(
            {
                "id": "task-kb",
                "type": "knowledge_research",
                "description": f"从内部知识库检索与「{query}」相关的片段",
                "dependsOn": [],
            }
        )
    tasks.append(
        {
            "id": "task-1",
            "type": "web_research",
            "description": f"收集与「{query}」相关的官方资料、功能对比与生态信息",
            "dependsOn": [],
        }
    )
    return Plan.model_validate({"title": f"调研：{query[:40]}",
                                "objective": query,
                                "tasks": tasks})


def canonical_plan_json(plan: Plan) -> str:
    return json.dumps(plan.model_dump(by_alias=True),
                      ensure_ascii=False,
                      sort_keys=True,
                      separators=(",", ":"))


def plan_hash(plan: Plan) -> str:
    return hashlib.sha256(canonical_plan_json(plan).encode("utf-8")).hexdigest()


def create_plan(state: ResearchState) -> dict[str, Any]:
    """
    Planner 节点：生成研究计划并写入状态。

    Returns:
        状态增量（plan / events / step_count 等）。
    """
    step, failure = claim_step(state, "create_plan")
    if failure is not None:
        return failure

    events = list(state.get("events") or [])
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

    settings = get_settings()

    has_kb = bool(state.get("knowledge_base_ids"))

    if settings.synthetic_allowed():
        plan = _mock_plan(state["user_query"], has_kb=has_kb)
    else:
        if not settings.deepseek_api_key.strip():
            raise RuntimeError("LLM_NOT_CONFIGURED")
        hint = "\n已绑定知识库" if has_kb else "\n未绑定知识库"
        response = get_chat_model(temperature=0.1,
                                  timeout_seconds=remaining_seconds(state, 60)).invoke([
            SystemMessage(content=_PLANNER_SYSTEM),
            HumanMessage(content=state["user_query"] + hint +
                                 ("\n修订意见：" + state["revision_instruction"]
                                  if state.get("revision_instruction") else "")),
        ])
        plan = Plan.model_validate(_extract_json(str(response.content)))
        _validate_new_plan(plan, has_kb=has_kb)

    digest = plan_hash(plan)
    plan_dict = plan.model_dump(by_alias=True)

    events.append(make_event(events=events,
                             task_id=task_id,
                             run_id=run_id,
                             event_type="PLAN_CREATED",
                             node="create_plan",
                             data={"plan": plan_dict,
                                   "planHash": digest,
                                   "planRevision": state.get("plan_revision") or 1,
                                   "revisionInstruction": state.get("revision_instruction"),
                                   "title": plan.title,
                                   "taskCount": len(plan.tasks)}
                             ))

    events.append(make_event(events=events,
                             task_id=task_id,
                             run_id=run_id,
                             event_type="NODE_COMPLETED",
                             node="create_plan",
                             data={"agent": "Planner"}
                             ))

    return {"plan": plan_dict,
            "plan_hash": digest,
            "clarified_query": plan.objective,
            "step_count": step,
            "events": events[len(state.get("events") or []):]}


def _validate_new_plan(plan: Plan, *, has_kb: bool) -> None:
    """Validate new LLM plans without breaking parsing of immutable historical plans."""
    if not 2 <= len(plan.tasks) <= 8:
        raise ValueError("new plan must contain between two and eight tasks")
    if len(plan.research_dimensions) < 2:
        raise ValueError("new plan must declare at least two research dimensions")
    if not has_kb and all(task.type == "knowledge_research" for task in plan.tasks):
        raise ValueError("knowledge-only plan requires a bound knowledge base")
    if any(len(task.description.strip()) < 12 for task in plan.tasks):
        raise ValueError("plan task must contain a concrete evidence objective")
