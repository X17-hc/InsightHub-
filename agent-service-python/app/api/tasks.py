"""Agent 任务 HTTP 路由（同步 + NDJSON 流式）。"""

from __future__ import annotations

import json
from collections.abc import Iterator

from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse

from app.core.config import get_settings
from app.schemas.protocol import (
    AgentError,
    AgentTaskRequest,
    AgentTaskResponse,
    PlanApprovalResumeRequest,
    ResumeTaskRequest,
    TaskControlRequest,
)
from app.services.control import ControlStoreUnavailable, get_control_store
from app.services.idempotency_store import IdempotencyStoreUnavailable, get_idempotency_store
from app.services.runner import approve_plan_research_task, resume_research_task, run_research_task, stream_research_task

router = APIRouter()

_IDEMPOTENCY_TTL_SECONDS = 24 * 60 * 60


def _claim_ttl_seconds() -> int:
    """RUNNING 标记只覆盖执行预算；进程崩溃后不会把同一请求锁死一整天。"""
    return max(600, get_settings().default_timeout_seconds + 600)


def _idempotency_unavailable(trace_id: str | None) -> JSONResponse:
    return JSONResponse(
        status_code=503,
        content=AgentError(
            code="IDEMPOTENCY_UNAVAILABLE",
            message="durable idempotency service is unavailable",
            traceId=trace_id,
        ).model_dump(by_alias=True),
    )


@router.get("/health")
def health() -> dict[str, str]:
    """健康检查。"""
    return {"status": "ok"}


@router.put("/internal/v1/agent/tasks/{task_id}/control")
def set_task_control(task_id: str, body: TaskControlRequest) -> dict[str, str]:
    """在 Agent 本机 Redis 写入控制字，避免 Java/Agent 误用不同 Redis 实例。"""
    try:
        get_control_store().set(task_id, body.value, body.ttl_seconds)
    except ControlStoreUnavailable as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "CONTROL_UNAVAILABLE", "message": "task control service is unavailable"},
        ) from exc
    return {"taskId": task_id, "value": body.value}


@router.post("/internal/v1/agent/tasks", response_model=AgentTaskResponse)
def create_agent_task(
    body: AgentTaskRequest,
    x_trace_id: str | None = Header(default=None, alias="X-Trace-Id"),
    x_idempotency_key: str | None = Header(default=None, alias="X-Idempotency-Key"),
) -> AgentTaskResponse | JSONResponse:
    """
    同步创建并执行研究任务。

    相同 X-Idempotency-Key 返回首次结果。
    """
    if not body.query or not body.query.strip():
        err = AgentError(
            code="VALIDATION_ERROR",
            message="query must not be empty",
            traceId=x_trace_id,
        )
        return JSONResponse(status_code=400, content=err.model_dump(by_alias=True))

    claim = None
    if x_idempotency_key:
        store_key = f"sync:{x_idempotency_key}"
        try:
            claim = get_idempotency_store().claim(store_key, _claim_ttl_seconds())
        except IdempotencyStoreUnavailable:
            return _idempotency_unavailable(x_trace_id)
        if not claim.acquired:
            if claim.state == "COMPLETED" and claim.response:
                return AgentTaskResponse.model_validate(claim.response)
            return JSONResponse(
                status_code=409,
                content=AgentError(
                    code="REQUEST_IN_PROGRESS",
                    message="same idempotency key is already running",
                    traceId=x_trace_id,
                ).model_dump(by_alias=True),
            )

    try:
        result = run_research_task(body, trace_id=x_trace_id)
    except Exception as exc:  # noqa: BLE001
        if x_idempotency_key and claim and claim.owner:
            try:
                get_idempotency_store().release(store_key, claim.owner)
            except IdempotencyStoreUnavailable:
                pass
        raise HTTPException(
            status_code=500,
            detail=AgentError(
                code="AGENT_EXECUTION_FAILED",
                message="agent execution failed",
                traceId=x_trace_id,
            ).model_dump(by_alias=True),
        ) from exc

    if x_idempotency_key and claim and claim.owner:
        try:
            get_idempotency_store().complete(
                store_key,
                claim.owner,
                result.model_dump(by_alias=True),
                _IDEMPOTENCY_TTL_SECONDS,
            )
        except IdempotencyStoreUnavailable:
            return _idempotency_unavailable(x_trace_id)

    return result


def _ndjson_lines(lines: Iterator[str]) -> Iterator[bytes]:
    """将字符串行转为带换行的 UTF-8 字节流。"""
    for line in lines:
        yield (line + "\n").encode("utf-8")


@router.post("/internal/v1/agent/tasks/stream", response_model=None)
def stream_agent_task(
    body: AgentTaskRequest,
    x_trace_id: str | None = Header(default=None, alias="X-Trace-Id"),
    x_idempotency_key: str | None = Header(default=None, alias="X-Idempotency-Key"),
) -> StreamingResponse | JSONResponse:
    """
    流式执行：application/x-ndjson，一行一个事件，末行 TASK_RESULT。
    """
    if not body.query or not body.query.strip():
        err = AgentError(
            code="VALIDATION_ERROR",
            message="query must not be empty",
            traceId=x_trace_id,
        )
        return JSONResponse(status_code=400, content=err.model_dump(by_alias=True))

    claim = None
    if x_idempotency_key:
        store_key = f"stream:{x_idempotency_key}"
        try:
            claim = get_idempotency_store().claim(store_key, _claim_ttl_seconds())
        except IdempotencyStoreUnavailable:
            return _idempotency_unavailable(x_trace_id)
        if not claim.acquired:
            code = "ALREADY_COMPLETED" if claim.state == "COMPLETED" else "STREAM_IN_PROGRESS"
            return JSONResponse(
                status_code=409,
                content=AgentError(
                    code=code,
                    message="idempotency key has already been accepted; read events via Java SSE",
                    traceId=x_trace_id,
                ).model_dump(by_alias=True),
            )

    def gen() -> Iterator[bytes]:
        terminal_status: str | None = None
        try:
            for line in stream_research_task(body, trace_id=x_trace_id):
                # 捕获 TASK_RESULT，供 finally 写入幂等终态标记
                try:
                    obj = json.loads(line)
                    if obj.get("type") == "TASK_RESULT" and obj.get("status"):
                        terminal_status = str(obj["status"])
                except (json.JSONDecodeError, TypeError, ValueError):
                    pass
                yield (line + "\n").encode("utf-8")
        finally:
            if x_idempotency_key and claim and claim.owner:
                try:
                    if terminal_status:
                        get_idempotency_store().complete(
                            store_key,
                            claim.owner,
                            {"taskId": body.task_id, "status": terminal_status, "streamFinished": True},
                            _IDEMPOTENCY_TTL_SECONDS,
                        )
                    else:
                        get_idempotency_store().release(store_key, claim.owner)
                except IdempotencyStoreUnavailable:
                    # HTTP 流可能已提交，不能再改写响应；服务端保持告警而不泄露 Redis 异常。
                    pass

    # 显式 charset=utf-8，避免中间代理/客户端按平台编码误读中文
    return StreamingResponse(gen(), media_type="application/x-ndjson; charset=utf-8")


@router.post("/internal/v1/agent/tasks/{task_id}/resume", response_model=None)
def resume_agent_task(
    task_id: str,
    body: ResumeTaskRequest | None = None,
    x_trace_id: str | None = Header(default=None, alias="X-Trace-Id"),
) -> StreamingResponse:
    """从 Checkpoint 恢复 NDJSON 流。"""
    req = body or ResumeTaskRequest()
    timeout = get_settings().default_timeout_seconds
    trace = req.trace_id or x_trace_id

    def gen() -> Iterator[bytes]:
        yield from _ndjson_lines(
            resume_research_task(
                task_id,
                run_id=req.run_id,
                trace_id=trace,
                timeout_seconds=timeout,
            )
        )

    return StreamingResponse(gen(), media_type="application/x-ndjson; charset=utf-8")


@router.post("/internal/v1/agent/tasks/{task_id}/plan/approve", response_model=None)
def approve_plan_agent_task(
    task_id: str, body: PlanApprovalResumeRequest,
    x_trace_id: str | None = Header(default=None, alias="X-Trace-Id"),
) -> StreamingResponse:
    def gen() -> Iterator[bytes]:
        yield from _ndjson_lines(approve_plan_research_task(
            task_id, run_id=body.run_id, approved_plan_hash=body.approved_plan_hash,
            trace_id=x_trace_id, timeout_seconds=get_settings().default_timeout_seconds))
    return StreamingResponse(gen(), media_type="application/x-ndjson; charset=utf-8")
