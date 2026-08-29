"""兼容层：dispatch / execute_plan 供单测验证 DAG。

主图不再调用本模块；调度算法在 policies.plan_scheduler，跳转在 runtime.supervisor。
"""

from __future__ import annotations

from typing import Any

from app.agents.knowledge_researcher import knowledge_research
from app.agents.policies.plan_scheduler import (
    KB_TYPES,
    WEB_TYPES,
    mark_skipped,
    next_ready_batch,
    seed_pending_from_plan,
    task_agent_name,
)
from app.agents.researcher import web_research
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState

_KB_TYPES = KB_TYPES
_WEB_TYPES = WEB_TYPES


def dispatch_tasks(state: ResearchState) -> dict[str, Any]:
    """从计划提取待执行任务（单测与旧节点兼容）。"""
    step, limit_failure = claim_step(state, "dispatch_tasks")
    if limit_failure is not None:
        return limit_failure
    events = list(state.get("events") or [])
    pending = seed_pending_from_plan(
        state.get("plan"),
        knowledge_base_ids=list(state.get("knowledge_base_ids") or []),
        query=str(state.get("clarified_query") or state.get("user_query") or ""),
    )
    delta = [
        make_event(
            events=events,
            task_id=state["task_id"],
            run_id=state["run_id"],
            event_type="NODE_STARTED",
            node="dispatch_tasks",
            data={"agent": "Supervisor"},
        ),
        make_event(
            events=events + [{}],
            task_id=state["task_id"],
            run_id=state["run_id"],
            event_type="NODE_COMPLETED",
            node="dispatch_tasks",
            data={"agent": "Supervisor", "pendingCount": len(pending)},
        ),
    ]
    # 第二事件 eventId 需基于第一事件
    delta[1] = make_event(
        events=events + [delta[0]],
        task_id=state["task_id"],
        run_id=state["run_id"],
        event_type="NODE_COMPLETED",
        node="dispatch_tasks",
        data={"agent": "Supervisor", "pendingCount": len(pending)},
    )
    return {
        "pending_tasks": pending,
        "completed_tasks": list(state.get("completed_tasks") or []),
        "step_count": step,
        "status": "RUNNING",
        "events": delta,
    }


def execute_plan(state: ResearchState) -> dict[str, Any]:
    """按 DAG 同步执行（单测用）。主图改为 Supervisor handoff 到专家。"""
    working: dict[str, Any] = dict(state)
    original_events = list(state.get("events") or [])
    delta: list[dict[str, Any]] = []
    remaining = [dict(item) for item in (state.get("pending_tasks") or [])]
    completed = list(state.get("completed_tasks") or [])
    max_parallelism = max(1, min(8, int(state.get("max_parallelism") or 3)))
    batch_no = 0

    while remaining:
        batch = next_ready_batch(remaining, completed, max_parallelism=max_parallelism)
        for item in batch.skipped:
            task_ref = str(item.get("id"))
            delta.append(
                make_event(
                    events=original_events + delta,
                    task_id=state["task_id"],
                    run_id=state["run_id"],
                    event_type="PLAN_TASK_SKIPPED",
                    node="execute_plan",
                    data={"planTaskId": task_ref, "taskType": item.get("type"), "reason": "SKIPPED_DEPENDENCY_FAILED"},
                )
            )
            completed.append(mark_skipped(item))
            remaining = [row for row in remaining if str(row.get("id")) != task_ref]
        if not remaining:
            break
        if batch.deadlock or not batch.ready:
            error = {"code": "PLAN_DEPENDENCY_DEADLOCK", "message": "plan dependencies cannot make progress"}
            delta.append(
                make_event(
                    events=original_events + delta,
                    task_id=state["task_id"],
                    run_id=state["run_id"],
                    event_type="TASK_FAILED",
                    node="execute_plan",
                    data=error,
                )
            )
            return {"status": "FAILED", "errors": [error], "completed_tasks": completed, "events": delta}

        batch_no += 1
        for item in batch.ready:
            task_ref = str(item.get("id"))
            delta.append(
                make_event(
                    events=original_events + delta,
                    task_id=state["task_id"],
                    run_id=state["run_id"],
                    event_type="PLAN_TASK_STARTED",
                    node="execute_plan",
                    data={"planTaskId": task_ref, "taskType": item.get("type"), "batchNo": batch_no},
                )
            )
            working.update({
                "pending_tasks": [item],
                "completed_tasks": completed,
                "events": original_events + delta,
            })
            handler = knowledge_research if task_agent_name(str(item.get("type"))) == "knowledge_research" else web_research
            result = handler(working)
            child_events = list(result.get("events") or [])
            delta.extend(child_events)
            if result.get("status") == "FAILED":
                completed.append({**item, "status": "FAILED", "evidenceCount": 0})
                remaining = [row for row in remaining if str(row.get("id")) != task_ref]
                delta.append(
                    make_event(
                        events=original_events + delta,
                        task_id=state["task_id"],
                        run_id=state["run_id"],
                        event_type="PLAN_TASK_FAILED",
                        node="execute_plan",
                        data={
                            "planTaskId": task_ref,
                            "taskType": item.get("type"),
                            "batchNo": batch_no,
                            "code": (result.get("errors") or [{}])[0].get("code", "PLAN_TASK_FAILED"),
                        },
                    )
                )
                while True:
                    blocked = next_ready_batch(remaining, completed, max_parallelism=8)
                    if not blocked.skipped:
                        break
                    for candidate in blocked.skipped:
                        blocked_id = str(candidate.get("id"))
                        completed.append(mark_skipped(candidate))
                        remaining = [row for row in remaining if str(row.get("id")) != blocked_id]
                        delta.append(
                            make_event(
                                events=original_events + delta,
                                task_id=state["task_id"],
                                run_id=state["run_id"],
                                event_type="PLAN_TASK_SKIPPED",
                                node="execute_plan",
                                data={
                                    "planTaskId": blocked_id,
                                    "taskType": candidate.get("type"),
                                    "batchNo": batch_no,
                                    "reason": "SKIPPED_DEPENDENCY_FAILED",
                                },
                            )
                        )
                return {**result, "completed_tasks": completed, "events": delta}
            working.update(result)
            completed = list(result.get("completed_tasks") or completed)
            evidence_count = next(
                (int(row.get("evidenceCount") or 0) for row in reversed(completed) if str(row.get("id")) == task_ref),
                0,
            )
            delta.append(
                make_event(
                    events=original_events + delta,
                    task_id=state["task_id"],
                    run_id=state["run_id"],
                    event_type="PLAN_TASK_COMPLETED",
                    node="execute_plan",
                    data={
                        "planTaskId": task_ref,
                        "taskType": item.get("type"),
                        "batchNo": batch_no,
                        "evidenceCount": evidence_count,
                    },
                )
            )
            remaining = [row for row in remaining if str(row.get("id")) != task_ref]

    return {
        "pending_tasks": [],
        "completed_tasks": completed,
        "evidence": list(working.get("evidence") or state.get("evidence") or []),
        "step_count": int(working.get("step_count") or state.get("step_count") or 0),
        "status": "RUNNING",
        "events": delta,
    }
