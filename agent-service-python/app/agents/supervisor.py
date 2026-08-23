"""Supervisor Agent：分派 knowledge_research / web_research 任务。"""

from __future__ import annotations

from typing import Any

from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.agents.knowledge_researcher import knowledge_research
from app.agents.researcher import web_research

_KB_TYPES = {"knowledge_research", "kb_research", "knowledge"}
_WEB_TYPES = {"web_research", "web-research", "research"}


def dispatch_tasks(state: ResearchState) -> dict[str, Any]:
    """
    Supervisor 节点：从计划中提取待执行研究任务。

    有知识库时确保至少一条 knowledge_research；否则仅 web_research。
    """
    step, limit_failure = claim_step(state, "dispatch_tasks")
    if limit_failure is not None:
        return limit_failure
    events = list(state.get("events") or [])
    task_id = state["task_id"]
    run_id = state["run_id"]
    kb_ids = list(state.get("knowledge_base_ids") or [])

    delta: list[dict[str, Any]] = []
    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_STARTED",
            node="dispatch_tasks",
            data={"agent": "Supervisor"},
        )
    )

    plan = state.get("plan") or {}
    tasks = list(plan.get("tasks") or [])
    pending = [
        t
        for t in tasks
        if (t.get("type") or "").lower() in (_KB_TYPES | _WEB_TYPES)
    ]
    if kb_ids and not any((t.get("type") or "").lower() in _KB_TYPES for t in pending):
        pending.insert(
            0,
            {
                "id": "task-kb",
                "type": "knowledge_research",
                "description": state.get("clarified_query") or state.get("user_query") or "",
                "dependsOn": [],
            },
        )
    if not pending:
        pending = [
            {
                "id": "task-1",
                "type": "web_research",
                "description": state.get("clarified_query") or state.get("user_query") or "",
                "dependsOn": [],
            }
        ]

    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_COMPLETED",
            node="dispatch_tasks",
            data={"agent": "Supervisor", "pendingCount": len(pending)},
        )
    )

    return {
        "pending_tasks": pending,
        "completed_tasks": list(state.get("completed_tasks") or []),
        "step_count": step,
        "status": "RUNNING",
        "events": delta,
    }


def execute_plan(state: ResearchState) -> dict[str, Any]:
    """按有界 DAG 的 ready 批次执行计划，保证 dependsOn 具有真实语义。"""
    working: dict[str, Any] = dict(state)
    original_events = list(state.get("events") or [])
    delta: list[dict[str, Any]] = []
    tasks = [dict(item) for item in (state.get("pending_tasks") or [])]
    completed = list(state.get("completed_tasks") or [])
    completed_ids = {str(item.get("id")) for item in completed if item.get("status") == "DONE"}
    failed_ids = {str(item.get("id")) for item in completed if item.get("status") == "FAILED"}
    remaining = {str(item.get("id")): item for item in tasks}
    batch_no = 0
    max_parallelism = max(1, min(8, int(state.get("max_parallelism") or 3)))

    while remaining:
        skipped = [
            item for item in remaining.values()
            if set(item.get("dependsOn") or ()) & failed_ids
        ]
        for item in skipped:
            task_ref = str(item.get("id"))
            delta.append(make_event(
                events=original_events + delta,
                task_id=state["task_id"], run_id=state["run_id"],
                event_type="PLAN_TASK_SKIPPED", node="execute_plan",
                data={"planTaskId": task_ref, "taskType": item.get("type"), "reason": "SKIPPED_DEPENDENCY_FAILED"},
            ))
            completed.append({**item, "status": "SKIPPED_DEPENDENCY_FAILED", "evidenceCount": 0})
            failed_ids.add(task_ref)
            remaining.pop(task_ref, None)
        if not remaining:
            break

        ready = [
            item for item in remaining.values()
            if set(item.get("dependsOn") or ()) <= completed_ids
        ][:max_parallelism]
        if not ready:
            error = {"code": "PLAN_DEPENDENCY_DEADLOCK", "message": "plan dependencies cannot make progress"}
            delta.append(make_event(events=original_events + delta, task_id=state["task_id"], run_id=state["run_id"],
                                    event_type="TASK_FAILED", node="execute_plan", data=error))
            return {"status": "FAILED", "errors": [error], "completed_tasks": completed, "events": delta}

        batch_no += 1
        for item in ready:
            task_ref = str(item.get("id"))
            delta.append(make_event(
                events=original_events + delta,
                task_id=state["task_id"], run_id=state["run_id"],
                event_type="PLAN_TASK_STARTED", node="execute_plan",
                data={"planTaskId": task_ref, "taskType": item.get("type"), "batchNo": batch_no},
            ))
            working.update({
                "pending_tasks": [item],
                "completed_tasks": completed,
                "events": original_events + delta,
            })
            handler = knowledge_research if str(item.get("type")).lower() in _KB_TYPES else web_research
            result = handler(working)
            child_events = list(result.get("events") or [])
            delta.extend(child_events)
            if result.get("status") == "FAILED":
                failed_ids.add(task_ref)
                completed.append({**item, "status": "FAILED", "evidenceCount": 0})
                remaining.pop(task_ref, None)
                delta.append(make_event(
                    events=original_events + delta,
                    task_id=state["task_id"], run_id=state["run_id"],
                    event_type="PLAN_TASK_FAILED", node="execute_plan",
                    data={"planTaskId": task_ref, "taskType": item.get("type"), "batchNo": batch_no,
                          "code": (result.get("errors") or [{}])[0].get("code", "PLAN_TASK_FAILED")},
                ))
                # Preserve an auditable terminal state for every transitive dependent.
                while True:
                    blocked = [candidate for candidate in remaining.values()
                               if set(candidate.get("dependsOn") or ()) & failed_ids]
                    if not blocked:
                        break
                    for candidate in blocked:
                        blocked_id = str(candidate.get("id"))
                        completed.append({**candidate, "status": "SKIPPED_DEPENDENCY_FAILED", "evidenceCount": 0})
                        failed_ids.add(blocked_id)
                        remaining.pop(blocked_id, None)
                        delta.append(make_event(
                            events=original_events + delta, task_id=state["task_id"], run_id=state["run_id"],
                            event_type="PLAN_TASK_SKIPPED", node="execute_plan",
                            data={"planTaskId": blocked_id, "taskType": candidate.get("type"),
                                  "batchNo": batch_no, "reason": "SKIPPED_DEPENDENCY_FAILED"},
                        ))
                return {**result, "completed_tasks": completed, "events": delta}
            working.update(result)
            completed = list(result.get("completed_tasks") or completed)
            completed_ids.add(task_ref)
            evidence_count = next((int(row.get("evidenceCount") or 0) for row in reversed(completed)
                                   if str(row.get("id")) == task_ref), 0)
            delta.append(make_event(
                events=original_events + delta,
                task_id=state["task_id"], run_id=state["run_id"],
                event_type="PLAN_TASK_COMPLETED", node="execute_plan",
                data={"planTaskId": task_ref, "taskType": item.get("type"), "batchNo": batch_no,
                      "evidenceCount": evidence_count},
            ))
            remaining.pop(task_ref, None)

    return {
        "pending_tasks": [],
        "completed_tasks": completed,
        "evidence": list(working.get("evidence") or state.get("evidence") or []),
        "step_count": int(working.get("step_count") or state.get("step_count") or 0),
        "status": "RUNNING",
        "events": delta,
    }
