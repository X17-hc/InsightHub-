"""Python 内部 API 的服务间共享密钥校验。"""

from __future__ import annotations

import hmac

from fastapi import Request
from fastapi.responses import JSONResponse

from app.core.config import get_settings


async def require_internal_token(request: Request, call_next):
    """仅保护 /internal/v1，健康检查保持可供编排器探测。"""
    if not request.url.path.startswith("/internal/v1/"):
        return await call_next(request)

    expected = get_settings().agent_internal_token
    provided = request.headers.get("X-Internal-Token", "")
    if not expected:
        return JSONResponse(
            status_code=503,
            content={"code": "INTERNAL_AUTH_NOT_CONFIGURED", "message": "internal API is disabled"},
        )
    if not hmac.compare_digest(provided, expected):
        return JSONResponse(
            status_code=401,
            content={"code": "UNAUTHORIZED", "message": "invalid internal token"},
        )
    return await call_next(request)
