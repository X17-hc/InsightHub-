"""执行研究图：同步 invoke + 流式 NDJSON。"""

from __future__ import annotations

import json
import logging
import time
import uuid
from collections.abc import Iterator
from typing import Any

from app.core.config import get_settings
from app.graph.builder import get_compiled_graph
from app.graph.events import make_event
from app.schemas.protocol import (
    AgentError,
    AgentEvent,
    AgentTaskRequest,
    AgentTaskResponse,
)
from app.services.control import (
    CONTROL_CANCELLED,
    CONTROL_PAUSED,
    CONTROL_RUNNING,
    get_control_store,
)

logger = logging.getLogger(__name__)


def _build_init_state(
    request: AgentTaskRequest,
    run_id: str,
    trace: str,
    initial_events: list[dict[str, Any]],
) -> dict[str, Any]:
    """构造图初始状态。"""
    return {
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


def _dumps_event(event: dict[str, Any]) -> str:
    """事件 dict → NDJSON 行。"""
    return json.dumps(event, ensure_ascii=False)


def _task_result_line(
    *,
    task_id: str,
    run_id: str,
    status: str,
    report_markdown: str | None,
    error: dict[str, Any] | None,
) -> str:
    """终态摘要行（type=TASK_RESULT）。"""
    payload = {
        "type": "TASK_RESULT",
        "taskId": task_id,
        "runId": run_id,
        "status": status,
        "reportMarkdown": report_markdown,
        "error": error,
    }
    return json.dumps(payload, ensure_ascii=False)


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
    init_state = _build_init_state(request, run_id, trace, initial_events)

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

    return _response_from_state(request.task_id, run_id, trace, final_state)


def _response_from_state(
    task_id: str,
    run_id: str,
    trace: str,
    final_state: dict[str, Any],
) -> AgentTaskResponse:
    """从终态组装同步响应。"""
    events_raw = list(final_state.get("events") or [])
    status = final_state.get("status") or ("COMPLETED" if final_state.get("report") else "FAILED")
    if status == "FAILED" or not final_state.get("report"):
        errors = final_state.get("errors") or [{"code": "UNKNOWN", "message": "no report"}]
        err0 = errors[0]
        if not any(e.get("type") == "TASK_FAILED" for e in events_raw):
            events_raw.append(
                make_event(
                    events=events_raw,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="TASK_FAILED",
                    data=err0,
                )
            )
        return AgentTaskResponse(
            taskId=task_id,
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
        taskId=task_id,
        runId=run_id,
        status="COMPLETED",
        reportMarkdown=final_state.get("report"),
        events=[AgentEvent.model_validate(e) for e in events_raw],
        error=None,
    )


def stream_research_task(
    request: AgentTaskRequest,
    trace_id: str | None = None,
) -> Iterator[str]:
    """
    流式执行：按节点边界产出 NDJSON 行，最后一行 TASK_RESULT。

    Yields:
        NDJSON 文本行（不含换行符由调用方拼接）。
    """
    run_id = f"run-{uuid.uuid4().hex[:12]}"
    trace = trace_id or f"trace-{uuid.uuid4().hex[:12]}"
    timeout = max(1, int(request.config.timeout_seconds or 300))
    store = get_control_store()
    # 仅在无控制字时初始化为 RUNNING，避免覆盖调用方已写入的 PAUSED/CANCELLED
    if not store.exists(request.task_id):
        store.set(request.task_id, CONTROL_RUNNING, ttl_seconds=timeout + 600)

    # retry 续号：用占位事件把 next_event_id 锚定到 DB 已有最大号之后
    seed_events: list[dict[str, Any]] = []
    next_eid = request.config.next_event_id
    if next_eid is not None and int(next_eid) > 1:
        seed_events = [{"eventId": int(next_eid) - 1}]

    started = make_event(
        events=seed_events,
        task_id=request.task_id,
        run_id=run_id,
        event_type="TASK_STARTED",
        node=None,
        data={"traceId": trace, "query": request.query},
    )
    yield _dumps_event(started)

    init_state = _build_init_state(request, run_id, trace, [started])
    yield from _stream_graph(
        task_id=request.task_id,
        run_id=run_id,
        trace=trace,
        timeout=timeout,
        input_state=init_state,
        resume=False,
    )


def resume_research_task(
    task_id: str,
    *,
    run_id: str | None = None,
    trace_id: str | None = None,
    timeout_seconds: int = 300,
) -> Iterator[str]:
    """
    从 MemorySaver Checkpoint 恢复流式执行。

    Args:
        task_id: 与创建时相同的 thread_id。
        run_id: 可选续跑 runId。
        trace_id: 可选链路 ID。
        timeout_seconds: 超时秒数。
    """
    timeout = max(1, int(timeout_seconds))
    store = get_control_store()
    store.set(task_id, CONTROL_RUNNING, ttl_seconds=timeout + 600)

    graph = get_compiled_graph()
    config = {"configurable": {"thread_id": task_id}}
    # 与 Checkpoint 对齐 runId / traceId，避免控制事件与节点事件不一致
    run = run_id
    trace = trace_id
    try:
        snap = graph.get_state(config)
        values = (snap.values if snap is not None else None) or {}
        if not run:
            run = values.get("run_id") or f"run-{uuid.uuid4().hex[:12]}"
        if not trace:
            trace = values.get("trace_id") or f"trace-{uuid.uuid4().hex[:12]}"
    except Exception:  # noqa: BLE001
        run = run or f"run-{uuid.uuid4().hex[:12]}"
        trace = trace or f"trace-{uuid.uuid4().hex[:12]}"

    # LangGraph：None 输入表示从 checkpoint 继续
    yield from _stream_graph(
        task_id=task_id,
        run_id=run,
        trace=trace,
        timeout=timeout,
        input_state=None,
        resume=True,
    )


def _persist_control_event(
    graph: Any,
    config: dict[str, Any],
    event: dict[str, Any],
    *,
    status: str | None = None,
) -> None:
    """
    将控制面事件写入 MemorySaver Checkpoint。

    events 字段带 reducer，只传新增事件列表，避免整表重放导致重复。
    """
    patch: dict[str, Any] = {"events": [event]}
    if status is not None:
        patch["status"] = status
    try:
        graph.update_state(config, patch)
    except Exception as exc:  # noqa: BLE001
        logger.warning("persist control event failed type=%s: %s", event.get("type"), exc)


def _stream_graph(
    *,
    task_id: str,
    run_id: str,
    trace: str,
    timeout: int,
    input_state: dict[str, Any] | None,
    resume: bool,
) -> Iterator[str]:
    """内部：驱动 graph.stream 并在节点边界检查控制字。"""
    graph = get_compiled_graph()
    store = get_control_store()
    settings = get_settings()
    # MOCK 时在节点边界停顿，给 Java pause/cancel 留窗口；单测可设 delay=0
    step_delay_ms = int(settings.agent_mock_step_delay_ms) if settings.agent_mock_llm else 0
    deadline = time.monotonic() + timeout
    # 新建流已先 yield TASK_STARTED，跳过 state 中前 1 条；resume 仅增量 flush
    last_event_count = 0 if resume else 1
    final_state: dict[str, Any] | None = None
    config = {"configurable": {"thread_id": task_id}}
    if resume:
        try:
            snap = graph.get_state(config)
            existing = list(((snap.values if snap else None) or {}).get("events") or [])
            last_event_count = len(existing)
        except Exception:  # noqa: BLE001
            last_event_count = 0

    try:
        stream_input: Any = input_state
        for chunk in graph.stream(stream_input, config=config, stream_mode="values"):
            if not isinstance(chunk, dict):
                continue
            final_state = chunk
            events = list(chunk.get("events") or [])
            # 仅 flush 新增事件
            if len(events) > last_event_count:
                for ev in events[last_event_count:]:
                    yield _dumps_event(ev)
                last_event_count = len(events)

            # 分段 sleep 并轮询控制字，避免整段 sleep 错过 pause/cancel
            if step_delay_ms > 0:
                sleep_deadline = time.monotonic() + step_delay_ms / 1000.0
                while time.monotonic() < sleep_deadline:
                    if store.get(task_id) in (CONTROL_PAUSED, CONTROL_CANCELLED):
                        break
                    if time.monotonic() > deadline:
                        break
                    time.sleep(0.05)

            if time.monotonic() > deadline:
                fail = make_event(
                    events=events,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="TASK_FAILED",
                    data={"code": "TIMEOUT", "message": "agent task timed out"},
                )
                _persist_control_event(graph, config, fail, status="FAILED")
                yield _dumps_event(fail)
                yield _task_result_line(
                    task_id=task_id,
                    run_id=run_id,
                    status="FAILED",
                    report_markdown=None,
                    error={"code": "TIMEOUT", "message": "agent task timed out", "traceId": trace},
                )
                return

            ctrl = store.get(task_id)
            if ctrl == CONTROL_CANCELLED:
                fail = make_event(
                    events=events,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="TASK_FAILED",
                    data={"code": "CANCELLED", "message": "task cancelled"},
                )
                _persist_control_event(graph, config, fail, status="CANCELLED")
                yield _dumps_event(fail)
                yield _task_result_line(
                    task_id=task_id,
                    run_id=run_id,
                    status="CANCELLED",
                    report_markdown=None,
                    error={"code": "CANCELLED", "message": "task cancelled", "traceId": trace},
                )
                return

            if ctrl == CONTROL_PAUSED:
                paused = make_event(
                    events=events,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="TASK_PAUSED",
                    data={"message": "paused at node boundary"},
                )
                # 必须写入 Checkpoint，否则 resume 会复用同一 eventId
                _persist_control_event(graph, config, paused, status="PAUSED")
                yield _dumps_event(paused)
                yield _task_result_line(
                    task_id=task_id,
                    run_id=run_id,
                    status="PAUSED",
                    report_markdown=None,
                    error=None,
                )
                return

    except Exception as exc:  # noqa: BLE001
        logger.exception("stream graph failed taskId=%s resume=%s", task_id, resume)
        events = list((final_state or {}).get("events") or [])
        fail = make_event(
            events=events,
            task_id=task_id,
            run_id=run_id,
            event_type="TASK_FAILED",
            data={"code": "AGENT_EXECUTION_FAILED", "message": str(exc)},
        )
        _persist_control_event(graph, config, fail, status="FAILED")
        yield _dumps_event(fail)
        yield _task_result_line(
            task_id=task_id,
            run_id=run_id,
            status="FAILED",
            report_markdown=None,
            error={"code": "AGENT_EXECUTION_FAILED", "message": str(exc), "traceId": trace},
        )
        return

    if final_state is None:
        fail = make_event(
            events=[],
            task_id=task_id,
            run_id=run_id,
            event_type="TASK_FAILED",
            data={"code": "NO_STATE", "message": "empty stream" if not resume else "no checkpoint"},
        )
        _persist_control_event(graph, config, fail, status="FAILED")
        yield _dumps_event(fail)
        yield _task_result_line(
            task_id=task_id,
            run_id=run_id,
            status="FAILED",
            report_markdown=None,
            error={
                "code": "NO_CHECKPOINT" if resume else "NO_STATE",
                "message": "empty stream or missing checkpoint",
                "traceId": trace,
            },
        )
        return

    events = list(final_state.get("events") or [])
    status = final_state.get("status") or ("COMPLETED" if final_state.get("report") else "FAILED")
    report = final_state.get("report")

    if status == "COMPLETED" and report:
        if not any(e.get("type") == "TASK_COMPLETED" for e in events):
            done = make_event(
                events=events,
                task_id=task_id,
                run_id=run_id,
                event_type="TASK_COMPLETED",
                data={"message": "done"},
            )
            _persist_control_event(graph, config, done, status="COMPLETED")
            yield _dumps_event(done)
            events = events + [done]
        yield _task_result_line(
            task_id=task_id,
            run_id=run_id,
            status="COMPLETED",
            report_markdown=report,
            error=None,
        )
        return

    errors = final_state.get("errors") or [{"code": "UNKNOWN", "message": "no report"}]
    err0 = errors[0] if isinstance(errors[0], dict) else {"code": "UNKNOWN", "message": str(errors[0])}
    if not any(e.get("type") == "TASK_FAILED" for e in events):
        fail = make_event(
            events=events,
            task_id=task_id,
            run_id=run_id,
            event_type="TASK_FAILED",
            data=err0,
        )
        _persist_control_event(graph, config, fail, status="FAILED")
        yield _dumps_event(fail)
    yield _task_result_line(
        task_id=task_id,
        run_id=run_id,
        status="FAILED",
        report_markdown=report,
        error={
            "code": str(err0.get("code") or "AGENT_EXECUTION_FAILED"),
            "message": str(err0.get("message") or "task failed"),
            "traceId": trace,
        },
    )
