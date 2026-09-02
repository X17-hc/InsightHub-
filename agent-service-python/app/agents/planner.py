"""Planner：只产出结构化计划。禁止挂搜索工具，避免规划阶段污染取证。"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from app.agents.policies.json_extract import extract_json_object
from app.agents.policies.plan_validate import plan_hash, validate_new_plan

# 单测兼容旧私有名
_validate_new_plan = validate_new_plan
from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.deadline import remaining_seconds
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.schemas.protocol import Plan

_PLANNER_SYSTEM = """你是 InsightHub Planner。只输出 JSON，不调用工具，不输出结论。
格式：{"title":"...","objective":"...","tasks":[
{"id":"task-1","type":"web_research|knowledge_research","description":"...","dependsOn":[]}
],"researchDimensions":["..."],"sourceRequirements":{"minVerifiedSources":3,"requireOfficialSources":true}}
新计划至少 2 个、最多 8 个任务；复杂主题必须拆分为独立取证维度；dependsOn 必须构成无环图；绑定知识库时至少包含一个 knowledge_research。"""


def _mock_plan(query: str, has_kb: bool) -> Plan:
    """生成测试/演示环境使用的确定性计划。

    production 会在任务入口拒绝 mock 配置，因此该函数不能作为 LLM
    或真实检索故障时的降级路径，避免把合成计划伪装成正式研究结果。
    """
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


def create_plan(state: ResearchState) -> dict[str, Any]:
    """
    Planner 节点：生成、校验研究 DAG，并写入稳定的计划哈希。

    除调用 LLM 外不执行检索 I/O。新计划必须先通过 ``validate_new_plan``；
    ``plan_hash`` 是 Java 审批恢复时的绑定依据，任何修订都必须产生新哈希，
    防止用户批准的版本与实际执行版本不一致。

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
        plan = Plan.model_validate(extract_json_object(str(response.content)))
        validate_new_plan(plan, has_kb=has_kb)

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
