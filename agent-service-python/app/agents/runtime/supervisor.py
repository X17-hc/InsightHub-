"""Supervisor 节点：决策 + 鉴权 + 发事件 + Command 跳转。"""

from __future__ import annotations

from typing import Any

from langgraph.graph import END
from langgraph.types import Command

from app.agents.policies.plan_scheduler import (
    mark_skipped,
    next_ready_batch,
    seed_pending_from_plan,
    task_agent_name,
)
from app.agents.runtime.handoff import (
    HandoffDenied,
    HandoffPolicy,
    default_allowlist,
    emit_handoff,
    max_handoffs_for,
)
from app.agents.runtime.names import KB_RESEARCHER, SUPERVISOR, SUPPLEMENT, WEB_RESEARCHER
from app.agents.runtime.ports import HandoffDecision
from app.agents.runtime.supervisor_policy import choose_supervisor_policy, constrain_supervisor_decision
from app.agents.runtime.views import project_supervisor_view
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState


def _fail(state: ResearchState, code: str, message: str, *, node: str = SUPERVISOR) -> dict[str, Any]:
    event = make_event(
        events=list(state.get("events") or []),
        task_id=state["task_id"],
        run_id=state["run_id"],
        event_type="TASK_FAILED",
        node=node,
        data={"code": code, "message": message},
    )
    return {
        "status": "FAILED",
        "errors": [{"code": code, "message": message}],
        "events": [event],
    }


def _ensure_pending(state: ResearchState) -> list[dict[str, Any]]:
    pending = list(state.get("pending_tasks") or [])
    if pending or not state.get("plan"):
        return pending
    if state.get("completed_tasks"):
        return pending
    return seed_pending_from_plan(
        state.get("plan"),
        knowledge_base_ids=list(state.get("knowledge_base_ids") or []),
        query=str(state.get("clarified_query") or state.get("user_query") or ""),
    )


def _apply_skips(
    state: ResearchState,
    pending: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    completed = list(state.get("completed_tasks") or [])
    batch = next_ready_batch(
        pending,
        completed,
        max_parallelism=int(state.get("max_parallelism") or 3),
    )
    events: list[dict[str, Any]] = []
    remaining = [item for item in pending]
    for item in batch.skipped:
        completed.append(mark_skipped(item))
        remaining = [row for row in remaining if str(row.get("id")) != str(item.get("id"))]
        events.append(
            make_event(
                events=list(state.get("events") or []) + events,
                task_id=state["task_id"],
                run_id=state["run_id"],
                event_type="PLAN_TASK_SKIPPED",
                node=SUPERVISOR,
                data={
                    "planTaskId": item.get("id"),
                    "taskType": item.get("type"),
                    "reason": "SKIPPED_DEPENDENCY_FAILED",
                },
            )
        )
    ready = list(batch.ready)
    if not ready and remaining:
        retry = next_ready_batch(
            remaining,
            completed,
            max_parallelism=int(state.get("max_parallelism") or 3),
        )
        ready = list(retry.ready)
        if retry.deadlock:
            return remaining, completed, events + [
                make_event(
                    events=list(state.get("events") or []) + events,
                    task_id=state["task_id"],
                    run_id=state["run_id"],
                    event_type="TASK_FAILED",
                    node=SUPERVISOR,
                    data={"code": "PLAN_DEPENDENCY_DEADLOCK", "message": "plan dependencies cannot make progress"},
                )
            ]
    return remaining, completed, events


def _handoff_work_id(decision: HandoffDecision, first_ready_id: str | None) -> str | None:
    """给防环用的工作单元：研究跳转带计划子任务 id，闸门节点为 None。"""
    if decision.plan_task_id:
        return str(decision.plan_task_id)
    if decision.target in {KB_RESEARCHER, WEB_RESEARCHER, SUPPLEMENT} and first_ready_id:
        return str(first_ready_id)
    return None


def run_supervisor(state: ResearchState) -> Any:
    """主管一步：claim_step → 调度投影 → 策略决策 → 白名单跳转。"""
    if state.get("status") == "FAILED":
        return Command(goto=END, update={})

    step, limit_failure = claim_step(state, SUPERVISOR)
    if limit_failure is not None:
        return Command(goto=END, update=limit_failure)

    pending = _ensure_pending(state)
    remaining, completed, skip_events = _apply_skips(state, pending)
    if any(event.get("type") == "TASK_FAILED" for event in skip_events):
        return Command(
            goto=END,
            update={
                "pending_tasks": remaining,
                "completed_tasks": completed,
                "step_count": step,
                "status": "FAILED",
                "errors": [{"code": "PLAN_DEPENDENCY_DEADLOCK", "message": "plan dependencies cannot make progress"}],
                "events": skip_events,
            },
        )

    working: ResearchState = {
        **state,
        "pending_tasks": remaining,
        "completed_tasks": completed,
        "step_count": step,
    }
    ready_batch = next_ready_batch(
        remaining,
        completed,
        max_parallelism=int(state.get("max_parallelism") or 3),
    )
    view = project_supervisor_view(working, list(ready_batch.ready))
    policy = choose_supervisor_policy(
        enable_web_search=bool(state.get("enable_web_search", True)),
        enable_data_analysis=bool(state.get("enable_data_analysis")),
    )
    decision = constrain_supervisor_decision(view, policy.decide(view))
    allowlist = default_allowlist(
        enable_web_search=bool(state.get("enable_web_search", True)),
        enable_data_analysis=bool(state.get("enable_data_analysis")),
    )
    handoff_policy = HandoffPolicy(allowlist, max_handoffs=max_handoffs_for(state))
    log = list(state.get("handoff_log") or [])
    work_id = _handoff_work_id(decision, view.first_ready_id)
    try:
        handoff_policy.allow(SUPERVISOR, decision.target)
        handoff_policy.guard_history(log, SUPERVISOR, decision.target, plan_task_id=work_id)
    except HandoffDenied as exc:
        failure = _fail(state, exc.code, str(exc))
        failure["events"] = skip_events + list(failure.get("events") or [])
        failure["step_count"] = step
        return Command(goto=END, update=failure)

    routed = HandoffDecision(
        decision.target,
        decision.reason,
        plan_task_id=work_id,
        state_patch=decision.state_patch,
    )
    handoff_event = emit_handoff(working, routed, source=SUPERVISOR)
    log_entry = {
        "from": SUPERVISOR,
        "to": decision.target,
        "reason": decision.reason,
        "step": step,
        "planTaskId": work_id,
    }
    patch = {
        "pending_tasks": remaining,
        "completed_tasks": completed,
        "step_count": step,
        "active_agent": decision.target,
        "handoff_log": [log_entry],
        "agent_inbox": {"planTaskId": decision.plan_task_id, "ready": [item.get("id") for item in ready_batch.ready]},
        "events": skip_events + [handoff_event],
        "status": "RUNNING",
        **decision.state_patch,
    }
    return Command(goto=decision.target, update=patch)
