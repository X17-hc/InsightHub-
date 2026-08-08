"""Agent 任务 HTTP 路由（同步 + NDJSON 流式）。"""

from __future__ import annotations

import json
from collections.abc import Iterator

from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse

from app.core.config import get_settings
from app.schemas.protocol import AgentError, AgentTaskRequest, AgentTaskResponse, ResumeTaskRequest
from app.services.runner import resume_research_task, run_research_task, stream_research_task

router = APIRouter()

# 第 1 周进程内幂等缓存：key -> response dict（同步）或流终态标记（流式）
_idempotency_cache: dict[str, dict] = {}
# 流式进行中的幂等键
_streaming_keys: set[str] = set()


@router.get("/health")
def health() -> dict[str, str]:
    """健康检查。"""
    return {"status": "ok"}


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

    if x_idempotency_key and x_idempotency_key in _idempotency_cache:
        return AgentTaskResponse.model_validate(_idempotency_cache[x_idempotency_key])

    try:
        result = run_research_task(body, trace_id=x_trace_id)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(
            status_code=500,
            detail=AgentError(
                code="AGENT_EXECUTION_FAILED",
                message=str(exc),
                traceId=x_trace_id,
            ).model_dump(by_alias=True),
        ) from exc

    if x_idempotency_key:
        _idempotency_cache[x_idempotency_key] = result.model_dump(by_alias=True)

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

    if x_idempotency_key:
        if x_idempotency_key in _streaming_keys:
            return JSONResponse(
                status_code=409,
                content=AgentError(
                    code="STREAM_IN_PROGRESS",
                    message="same idempotency key is already streaming; read events via Java SSE",
                    traceId=x_trace_id,
                ).model_dump(by_alias=True),
            )
        if x_idempotency_key in _idempotency_cache:
            return JSONResponse(
                status_code=409,
                content=AgentError(
                    code="ALREADY_COMPLETED",
                    message="idempotency key already completed; use Java SSE for events",
                    traceId=x_trace_id,
                ).model_dump(by_alias=True),
            )
        _streaming_keys.add(x_idempotency_key)

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
            if x_idempotency_key:
                _streaming_keys.discard(x_idempotency_key)
                # 流结束后缓存终态，避免同 key 重跑覆盖 Checkpoint
                if terminal_status:
                    _idempotency_cache[x_idempotency_key] = {
                        "taskId": body.task_id,
                        "status": terminal_status,
                        "streamFinished": True,
                    }

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
