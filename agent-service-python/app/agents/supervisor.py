"""Supervisor Agent：分派 knowledge_research / web_research 任务。"""

from __future__ import annotations

from typing import Any

from app.graph.events import make_event
from app.graph.state import ResearchState

_KB_TYPES = {"knowledge_research", "kb_research", "knowledge"}
_WEB_TYPES = {"web_research", "web-research", "research"}


def dispatch_tasks(state: ResearchState) -> dict[str, Any]:
    """
    Supervisor 节点：从计划中提取待执行研究任务。

    有知识库时确保至少一条 knowledge_research；否则仅 web_research。
    """
    events = list(state.get("events") or [])
    step = int(state.get("step_count") or 0) + 1
    task_id = state["task_id"]
    run_id = state["run_id"]
    max_steps = int(state.get("max_steps") or 20)
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

    if step > max_steps:
        delta.append(
            make_event(
                events=events + delta,
                task_id=task_id,
                run_id=run_id,
                event_type="TASK_FAILED",
                node="dispatch_tasks",
                data={"code": "MAX_STEPS_EXCEEDED", "stepCount": step},
            )
        )
        return {
            "step_count": step,
            "status": "FAILED",
            "errors": [{"code": "MAX_STEPS_EXCEEDED", "message": f"step_count={step} > max_steps={max_steps}"}],
            "events": delta,
        }

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
