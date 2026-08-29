"""执行研究图：同步 invoke + 流式 NDJSON。"""

from __future__ import annotations

import logging
import hmac
import time
from collections.abc import Iterator
from typing import Any
from urllib import request

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
from app.services.execution_lease import TaskExecutionConflict, hold_task_execution
from app.services.checkpoint_service import checkpoint_values, patch_control_event as _persist_control_event, reset_checkpoint
from app.services.checkpoint_service import checkpoint_thread_id
from langgraph.types import Command
from app.services.event_service import dumps_event as _dumps_event, task_result_line as _task_result_line
from app.services.task_response_builder import response_from_state
from app.services.execution_context import ExecutionContext
from app.schemas.protocol import AgentEvent, AgentTaskRequest, AgentTaskResponse, Plan

logger = logging.getLogger(__name__)

_STABLE_EXECUTION_ERRORS = {
    "CRITIC_RESPONSE_INVALID": "critic response did not match the required schema",
    "LLM_UNAVAILABLE": "LLM service is temporarily unavailable",
    "LLM_NOT_CONFIGURED": "LLM configuration is unavailable",
    "HANDOFF_DENIED": "agent handoff was denied",
    "HANDOFF_LIMIT": "agent handoff limit exceeded",
    "AGENT_OUTPUT_INVALID": "agent did not produce a valid tool result",
}


def _classify_execution_failure(exception: Exception) -> tuple[str, str]:
    """将允许公开的稳定异常分类映射到协议，其他异常继续使用通用错误。"""
    if isinstance(exception, TimeoutError):
        return "TIMEOUT", "agent task timed out"
    raw_code = str(exception).strip().split(":", 1)[0]
    if raw_code in _STABLE_EXECUTION_ERRORS:
        return raw_code, _STABLE_EXECUTION_ERRORS[raw_code]
    return "AGENT_EXECUTION_FAILED", "agent execution failed"


def _configuration_error() -> tuple[str, str] | None:
    settings = get_settings()
    if settings.synthetic_allowed():
        return None
    if settings.agent_mock_llm or not settings.deepseek_api_key.strip():
        return "LLM_NOT_CONFIGURED", "LLM configuration is unavailable"
    if settings.is_production() and not settings.tavily_api_key.strip():
        return "SEARCH_NOT_CONFIGURED", "production search configuration is unavailable"
    return None

def _build_init_state(
    request,
    context,
    initial_events,
):
    """构造图初始状态。"""
    return {
        "task_id": request.task_id,
        "workspace_id": request.workspace_id,
        "user_id": request.user_id,
        "run_id": context.run_id,
        "trace_id": context.trace_id,
        "user_query": request.query,
        "clarified_query": None,
        "phase": request.phase,
        "require_plan_approval": request.config.require_plan_approval,
        "plan_revision": request.plan_revision or 1,
        "revision_instruction": request.revision_instruction,
        "plan": None,
        "plan_hash": None,
        "approved": request.phase == "EXECUTE",
        "pending_tasks": [],
        "completed_tasks": [],
        "evidence": [],
        "analysis_artifacts": [],
        "critique": None,
        "quality": None,
        "report": None,
        "step_count": 0,
        "retry_count": 0,
        "max_steps": request.config.max_steps,
        "max_parallelism": request.config.max_parallelism,
        "critic_round": 0,
        "max_critic_rounds": request.config.max_critic_rounds,
        "verified_evidence_ids": [],
        "deadline_at": context.deadline_at,
        "enable_web_search": request.config.enable_web_search,
        "enable_data_analysis": request.config.enable_data_analysis,
        "knowledge_base_ids": list(request.knowledge_base_ids),
        "citations": [],
        "errors": [],
        "status": "RUNNING",
        "events": initial_events,
        "active_agent": "supervisor",
        "handoff_log": [],
        "agent_inbox": {},
        "needs_verify": False,
        "needs_critique": False,
    }


def run_research_task(request: AgentTaskRequest, trace_id: str | None = None) -> AgentTaskResponse:
    """
    同步执行 Supervisor + 专家图。租约 / Checkpoint / 控制字仍是协议边界，不在此重写。

    Args:
        request: 协议请求体。
        trace_id: 来自 X-Trace-Id 的链路 ID。

    Returns:
        AgentTaskResponse（COMPLETED 或 FAILED）。
    """
    context = ExecutionContext.create(request, trace_id)
    run_id = context.run_id
    trace = context.trace_id
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
    configuration_error = _configuration_error()
    if configuration_error:
        code, message = configuration_error
        failed = make_event(events=initial_events, task_id=request.task_id, run_id=run_id,
                            event_type="TASK_FAILED", node=None, data={"code": code, "message": message})
        return AgentTaskResponse(taskId=request.task_id, runId=run_id, status="FAILED", reportMarkdown=None,
                                 events=[AgentEvent.model_validate(item) for item in initial_events + [failed]],
                                 error=AgentError(code=code, message=message, traceId=trace))
    init_state = _build_init_state(request, context, initial_events)

    try:
        with hold_task_execution(request.task_id, context.timeout_seconds + 600):
            final_state = graph.invoke(
                init_state,
                config={"configurable": {"thread_id": checkpoint_thread_id(request.task_id, run_id)}},
            )
    except TaskExecutionConflict as exc:
        fail_event = make_event(
            events=initial_events,
            task_id=request.task_id,
            run_id=run_id,
            event_type="TASK_FAILED",
            node=None,
            data={"code": "TASK_ALREADY_RUNNING", "message": str(exc)},
        )
        return AgentTaskResponse(
            taskId=request.task_id,
            runId=run_id,
            status="FAILED",
            reportMarkdown=None,
            events=[AgentEvent.model_validate(e) for e in (initial_events + [fail_event])],
            error=AgentError(code="TASK_ALREADY_RUNNING", message=str(exc), traceId=trace),
        )
    except Exception as exc:  # noqa: BLE001
        code, message = _classify_execution_failure(exc)
        logger.error("graph execution failed taskId=%s traceId=%s errorType=%s errorCode=%s",
                     request.task_id, trace, type(exc).__name__, code)
        fail_event = make_event(
            events=initial_events,
            task_id=request.task_id,
            run_id=run_id,
            event_type="TASK_FAILED",
            node=None,
            data={"code": code, "message": message},
        )
        return AgentTaskResponse(
            taskId=request.task_id,
            runId=run_id,
            status="FAILED",
            reportMarkdown=None,
            events=[AgentEvent.model_validate(e) for e in (initial_events + [fail_event])],
            error=AgentError(
                code=code,
                message=message,
                traceId=trace,
            ),
        )

    if isinstance(final_state, dict) and "__interrupt__" in final_state:
        values = checkpoint_values(graph, request.task_id, run_id)
        approval_event = make_event(
            events=list(values.get("events") or []),
            task_id=request.task_id,
            run_id=run_id,
            event_type="APPROVAL_REQUIRED",
            node="wait_for_approval",
            data={
                "planRevision": values.get("plan_revision") or 1,
                "planHash": values.get("plan_hash"),
            },
        )
        _persist_control_event(graph, {"configurable": {"thread_id": checkpoint_thread_id(request.task_id, run_id)}}, approval_event,
                               status="WAITING_APPROVAL")
        final_state = {
            **values,
            "status": "WAITING_APPROVAL",
            "events": list(values.get("events") or []) + [approval_event],
        }

    return response_from_state(request.task_id, run_id, trace, final_state)


def stream_research_task(
    request: AgentTaskRequest,
    trace_id: str | None = None,
) -> Iterator[str]:
    """
    流式执行：按节点边界产出 NDJSON 行，最后一行 TASK_RESULT。

    Yields:
        NDJSON 文本行（不含换行符由调用方拼接）。
    """
    context = ExecutionContext.create(request, trace_id)
    run_id = context.run_id
    trace = context.trace_id
    timeout = context.timeout_seconds
    store = get_control_store()
    configuration_error = _configuration_error()
    if configuration_error:
        code, message = configuration_error
        failed = make_event(events=[], task_id=request.task_id, run_id=run_id,
                            event_type="TASK_FAILED", node=None, data={"code": code, "message": message})
        yield _dumps_event(failed)
        yield _task_result_line(task_id=request.task_id, run_id=run_id, status="FAILED",
                                report_markdown=None, error={"code": code, "message": message, "traceId": trace})
        return
    # 仅在无控制字时初始化为 RUNNING，避免覆盖调用方已写入的 PAUSED/CANCELLED
    if not store.exists(request.task_id):
        store.set(request.task_id, CONTROL_RUNNING, ttl_seconds=timeout + 600)

    try:
        with hold_task_execution(request.task_id, timeout + 600):
            # retry 续号：清除旧图状态，并用占位事件锚定 DB 已有最大号
            seed_events: list[dict[str, Any]] = []
            next_eid = request.config.next_event_id
            if next_eid is not None and int(next_eid) > 1:
                reset_checkpoint(request.task_id, run_id)
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

            init_state = _build_init_state(request, context, [started])
            yield from _stream_graph(
                task_id=request.task_id,
                run_id=run_id,
                trace=trace,
                timeout=timeout,
                input_state=init_state,
                resume=False,
            )
    except TaskExecutionConflict as exc:
        yield _task_result_line(
            task_id=request.task_id,
            run_id=run_id,
            status="FAILED",
            report_markdown=None,
            error={"code": "TASK_ALREADY_RUNNING", "message": str(exc), "traceId": trace},
        )


def resume_research_task(
    task_id: str,
    *,
    run_id: str | None = None,
    trace_id: str | None = None,
    timeout_seconds: int = 300,
    final_state=None) -> Iterator[str]:
    """
    从 MemorySaver Checkpoint 恢复流式执行。

    Args:
        task_id: 与创建时相同的 thread_id。
        run_id: 可选续跑 runId。
        trace_id: 可选链路 ID。
        timeout_seconds: 超时秒数。
    """
    context = ExecutionContext.for_resume(
        task_id,
        run_id=run_id,
        trace_id=trace_id,
        timeout_seconds=timeout_seconds,
    )
    timeout = context.timeout_seconds
    store = get_control_store()
    store.set(task_id, CONTROL_RUNNING, ttl_seconds=timeout + 600)

    run = context.run_id
    trace = context.trace_id
    try:
        with hold_task_execution(task_id, timeout + 600):
            graph = get_compiled_graph()
            values = final_state or checkpoint_values(graph, task_id, run)
            if not values:
                yield _task_result_line(
                    task_id=task_id,
                    run_id=run,
                    status="FAILED",
                    report_markdown=None,
                    error={"code": "NO_CHECKPOINT", "message": "checkpoint not found", "traceId": trace},
                )
                return
            yield from _stream_graph(
                task_id=task_id,
                run_id=run,
                trace=trace,
                timeout=timeout,
                input_state=None,
                resume=True,
            )
    except TaskExecutionConflict as exc:
        yield _task_result_line(
            task_id=task_id,
            run_id=run,
            status="FAILED",
            report_markdown=None,
            error={"code": "TASK_ALREADY_RUNNING", "message": str(exc), "traceId": trace},
        )


def approve_plan_research_task(
    task_id: str, *, run_id: str, approved_plan_hash: str, trace_id: str | None = None,
    timeout_seconds: int = 300,
) -> Iterator[str]:
    """只允许通过匹配的计划哈希从审批中断点继续。"""
    context = ExecutionContext.for_resume(task_id, run_id=run_id, trace_id=trace_id, timeout_seconds=timeout_seconds)
    graph = get_compiled_graph()
    values = checkpoint_values(graph, task_id, run_id)
    stored_hash = str((values or {}).get("plan_hash") or "")
    if not values or not hmac.compare_digest(stored_hash, approved_plan_hash):
        yield _task_result_line(task_id=task_id, run_id=run_id, status="FAILED", report_markdown=None,
                                error={"code": "PLAN_HASH_MISMATCH", "message": "plan checkpoint does not match approval", "traceId": context.trace_id})
        return
    try:
        with hold_task_execution(task_id, context.timeout_seconds + 600):
            yield from _stream_graph(task_id=task_id, run_id=run_id, trace=context.trace_id,
                                     timeout=context.timeout_seconds,
                                     input_state=Command(resume={"approved": True, "approvedPlanHash": approved_plan_hash}),
                                     resume=True)
    except TaskExecutionConflict as exc:
        yield _task_result_line(task_id=task_id, run_id=run_id, status="FAILED", report_markdown=None,
                                error={"code": "TASK_ALREADY_RUNNING", "message": str(exc), "traceId": context.trace_id})


def _stream_graph(
    *,
    task_id: str,
    run_id: str,
    trace: str,
    timeout: int,
    input_state: Any,
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
    config = {"configurable": {"thread_id": checkpoint_thread_id(task_id, run_id)}}
    emitted_event_ids: set[int] = set()
    if resume:
        try:
            snap = graph.get_state(config)
            existing = list(((snap.values if snap else None) or {}).get("events") or [])
            last_event_count = len(existing)
        except Exception:  # noqa: BLE001
            last_event_count = 0

    try:
        stream_input: Any = input_state
        for stream_item in graph.stream(stream_input, config=config, stream_mode=["custom", "values"]):
            mode = "values"
            chunk = stream_item
            if isinstance(chunk, dict) and "__interrupt__" in chunk:
                values = checkpoint_values(graph, task_id, run_id)
                approval_event = make_event(
                    events=list(values.get("events") or []), task_id=task_id,
                    run_id=run_id, event_type="APPROVAL_REQUIRED",
                    node="wait_for_approval",
                    data={"planRevision": values.get("plan_revision") or 1,
                          "planHash": values.get("plan_hash")},
                )
                _persist_control_event(graph, config, approval_event, status="WAITING_APPROVAL")
                yield _dumps_event(approval_event)
                yield _task_result_line(
                    task_id=task_id, run_id=run_id, status="WAITING_APPROVAL",
                    report_markdown=None, error=None,
                    plan=values.get("plan"), plan_hash=values.get("plan_hash"),
                    plan_revision=values.get("plan_revision") or 1,
                )
                return

            if isinstance(stream_item, tuple) and len(stream_item) == 2:
                mode, chunk = stream_item

            if isinstance(chunk, dict) and "__interrupt__" in chunk:
                values = checkpoint_values(graph, task_id, run_id)
                approval_event = make_event(
                    events=list(values.get("events") or []), task_id=task_id,
                    run_id=run_id, event_type="APPROVAL_REQUIRED",
                    node="wait_for_approval",
                    data={"planRevision": values.get("plan_revision") or 1,
                          "planHash": values.get("plan_hash")},
                )
                _persist_control_event(graph, config, approval_event, status="WAITING_APPROVAL")
                yield _dumps_event(approval_event)
                yield _task_result_line(
                    task_id=task_id, run_id=run_id, status="WAITING_APPROVAL",
                    report_markdown=None, error=None, plan=values.get("plan"),
                    plan_hash=values.get("plan_hash"), plan_revision=values.get("plan_revision") or 1)
                return

            if mode == "custom":
                if isinstance(chunk, dict) and chunk.get("type") and chunk.get("eventId") is not None:
                    try:
                        event_id = int(chunk.get("eventId") or 0)
                    except (TypeError, ValueError):
                        event_id = 0
                    if event_id > 0 and event_id not in emitted_event_ids:
                        emitted_event_ids.add(event_id)
                        yield _dumps_event(chunk)
                continue

            if mode != "values":
                continue
            if not isinstance(chunk, dict):
                continue
            final_state = chunk
            events = list(chunk.get("events") or [])
            # 仅 flush 新增事件
            if len(events) > last_event_count:
                for ev in events[last_event_count:]:
                    try:
                        event_id = int(ev.get("eventId") or 0)
                    except (TypeError, ValueError):
                        event_id = 0
                    if event_id > 0 and event_id in emitted_event_ids:
                        continue
                    if event_id > 0:
                        emitted_event_ids.add(event_id)
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
        code, message = _classify_execution_failure(exc)
        logger.error("stream graph failed taskId=%s resume=%s traceId=%s errorType=%s errorCode=%s",
                     task_id, resume, trace, type(exc).__name__, code)
        events = list((final_state or {}).get("events") or [])
        fail = make_event(
            events=events,
            task_id=task_id,
            run_id=run_id,
            event_type="TASK_FAILED",
            data={"code": code, "message": message},
        )
        _persist_control_event(graph, config, fail, status="FAILED")
        yield _dumps_event(fail)
        yield _task_result_line(
            task_id=task_id,
            run_id=run_id,
            status="FAILED",
            report_markdown=None,
            error={"code": code, "message": message, "traceId": trace},
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
            citations=list(final_state.get("citations") or []),
            quality=final_state.get("quality"),
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
