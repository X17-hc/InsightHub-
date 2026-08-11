"""同步任务响应和错误响应构造。"""

from __future__ import annotations

from typing import Any

from app.graph.events import make_event
from app.schemas.protocol import AgentError, AgentEvent, AgentTaskResponse


def response_from_state(
    task_id: str,
    run_id: str,
    trace_id: str,
    state: dict[str, Any],
) -> AgentTaskResponse:
    """将 LangGraph 终态转换为稳定的 AgentTaskResponse。"""
    events = list(state.get("events") or [])
    status = state.get("status") or ("COMPLETED" if state.get("report") else "FAILED")
    report = state.get("report")
    if status == "FAILED" or not report:
        errors = state.get("errors") or [{"code": "UNKNOWN", "message": "no report"}]
        first = errors[0] if isinstance(errors[0], dict) else {"code": "UNKNOWN", "message": str(errors[0])}
        if not any(event.get("type") == "TASK_FAILED" for event in events):
            events.append(
                make_event(
                    events,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="TASK_FAILED",
                    data=first,
                )
            )
        return AgentTaskResponse(
            taskId=task_id,
            runId=run_id,
            status="FAILED",
            reportMarkdown=report,
            events=[AgentEvent.model_validate(event) for event in events],
            citations=list(state.get("citations") or []),
            error=AgentError(
                code=str(first.get("code") or "AGENT_EXECUTION_FAILED"),
                message=str(first.get("message") or "task failed"),
                traceId=trace_id,
                details=first,
            ),
        )
    return AgentTaskResponse(
        taskId=task_id,
        runId=run_id,
        status="COMPLETED",
        reportMarkdown=report,
        events=[AgentEvent.model_validate(event) for event in events],
        citations=list(state.get("citations") or []),
        error=None,
    )


def error_response(
    *,
    task_id: str,
    run_id: str,
    trace_id: str,
    code: str,
    message: str,
) -> AgentTaskResponse:
    """构造统一失败响应。"""
    started = make_event([], task_id=task_id, run_id=run_id, event_type="TASK_STARTED", data={})
    failed = make_event([started], task_id=task_id, run_id=run_id, event_type="TASK_FAILED", data={"code": code, "message": message})
    return AgentTaskResponse(
        taskId=task_id,
        runId=run_id,
        status="FAILED",
        reportMarkdown=None,
        events=[AgentEvent.model_validate(started), AgentEvent.model_validate(failed)],
        error=AgentError(code=code, message=message, traceId=trace_id),
    )
