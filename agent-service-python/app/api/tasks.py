"""Agent 任务 HTTP 路由。"""

from __future__ import annotations

from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import JSONResponse

from app.schemas.protocol import AgentError, AgentTaskRequest, AgentTaskResponse
from app.services.runner import run_research_task

router = APIRouter()

# 第 1 周进程内幂等缓存：key -> response dict
_idempotency_cache: dict[str, dict] = {}


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
