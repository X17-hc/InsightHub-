"""执行研究图并组装协议响应。"""

from __future__ import annotations

import uuid
from typing import Any

from app.graph.builder import get_compiled_graph
from app.graph.events import make_event
from app.schemas.protocol import (
    AgentError,
    AgentEvent,
    AgentTaskRequest,
    AgentTaskResponse,
)


def run_research_task(request: AgentTaskRequest, trace_id: str | None = None) -> AgentTaskResponse:
    """
    同步执行 Planner/Supervisor/Researcher 最小链路。

    Args:
        request: 协议请求体。
        trace_id: 来自 X-Trace-Id 的链路 ID。

    Returns:
        AgentTaskResponse（COMPLETED 或 FAILED）。
    """
    run_id = f"run-{uuid.uuid4().hex[:12]}"
    trace = trace_id or f"trace-{uuid.uuid4().hex[:12]}"
    graph = get_compiled_graph()

    initial_events = [
        make_event(
            events=[],
            task_id=request.task_id,
            run_id=run_id,
            event_type="TASK_STARTED",
            node=None,
            data={"traceId": trace, "query": request.query},
        )
    ]

    init_state: dict[str, Any] = {
        "task_id": request.task_id,
        "workspace_id": request.workspace_id,
        "user_id": request.user_id,
        "run_id": run_id,
        "trace_id": trace,
        "user_query": request.query,
        "clarified_query": None,
        "plan": None,
        "approved": False,
        "pending_tasks": [],
        "completed_tasks": [],
        "evidence": [],
        "analysis_artifacts": [],
        "critique": None,
        "report": None,
        "step_count": 0,
        "retry_count": 0,
        "max_steps": request.config.max_steps,
        "enable_web_search": request.config.enable_web_search,
        "errors": [],
        "status": "RUNNING",
        "events": initial_events,
    }

    try:
        final_state = graph.invoke(
            init_state,
            config={"configurable": {"thread_id": request.task_id}},
        )
    except Exception as exc:  # noqa: BLE001
        fail_event = make_event(
            events=initial_events,
            task_id=request.task_id,
            run_id=run_id,
            event_type="TASK_FAILED",
            node=None,
            data={"message": str(exc)},
        )
        return AgentTaskResponse(
            taskId=request.task_id,
            runId=run_id,
            status="FAILED",
            reportMarkdown=None,
            events=[AgentEvent.model_validate(e) for e in (initial_events + [fail_event])],
            error=AgentError(
                code="AGENT_EXECUTION_FAILED",
                message=str(exc),
                traceId=trace,
            ),
        )

    events_raw = list(final_state.get("events") or [])
    status = final_state.get("status") or ("COMPLETED" if final_state.get("report") else "FAILED")
    if status == "FAILED" or not final_state.get("report"):
        errors = final_state.get("errors") or [{"code": "UNKNOWN", "message": "no report"}]
        err0 = errors[0]
        if not any(e.get("type") == "TASK_FAILED" for e in events_raw):
            events_raw.append(
                make_event(
                    events=events_raw,
                    task_id=request.task_id,
                    run_id=run_id,
                    event_type="TASK_FAILED",
                    data=err0,
                )
            )
        return AgentTaskResponse(
            taskId=request.task_id,
            runId=run_id,
            status="FAILED",
            reportMarkdown=final_state.get("report"),
            events=[AgentEvent.model_validate(e) for e in events_raw],
            error=AgentError(
                code=str(err0.get("code") or "AGENT_EXECUTION_FAILED"),
                message=str(err0.get("message") or "task failed"),
                traceId=trace,
                details=err0 if isinstance(err0, dict) else {},
            ),
        )

    return AgentTaskResponse(
        taskId=request.task_id,
        runId=run_id,
        status="COMPLETED",
        reportMarkdown=final_state.get("report"),
        events=[AgentEvent.model_validate(e) for e in events_raw],
        error=None,
    )
