"""FastAPI 入口。"""

from __future__ import annotations

import shutil
import socket
import subprocess
from urllib.parse import urlparse

from dotenv import load_dotenv
from fastapi import FastAPI, Response, status

from app.api.knowledge import router as knowledge_router
from app.api.artifacts import router as artifacts_router
from app.api.tasks import router as tasks_router
from app.core.config import REPO_ROOT
from app.core.config import get_settings
from app.core.internal_auth import require_internal_token

# 加载仓库根目录 .env
load_dotenv(REPO_ROOT / ".env")
load_dotenv()

app = FastAPI(title="InsightHub Agent Service", version="0.1.0")
app.middleware("http")(require_internal_token)
app.include_router(tasks_router)
app.include_router(knowledge_router)
app.include_router(artifacts_router)


@app.get("/health")
def health() -> dict[str, str]:
    """Unauthenticated liveness endpoint for the local service manager."""
    return {"status": "ok"}


@app.get("/health/live")
def liveness() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready")
def readiness(response: Response) -> dict[str, object]:
    settings = get_settings()
    errors = settings.readiness_errors()
    components: dict[str, str] = {"configuration": "DOWN" if errors else "UP"}
    redis = urlparse(settings.redis_url)
    components["redis"] = _tcp_status(redis.hostname or "127.0.0.1", redis.port or 6379)
    components["postgres"] = _tcp_status(settings.postgres_host, settings.postgres_port)
    if components["redis"] == "DOWN":
        errors.append("REDIS_UNAVAILABLE")
    if components["postgres"] == "DOWN":
        errors.append("POSTGRES_UNAVAILABLE")
    if settings.sandbox_enabled:
        components["sandbox"] = _sandbox_status(settings.sandbox_image)
        if components["sandbox"] == "DOWN":
            errors.append("SANDBOX_UNAVAILABLE")
    else:
        components["sandbox"] = "DISABLED"
    if errors:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"status": "DOWN", "components": components, "errors": sorted(set(errors))}
    return {"status": "UP", "components": components, "errors": []}


def _tcp_status(host: str, port: int) -> str:
    try:
        with socket.create_connection((host, port), timeout=1.5):
            return "UP"
    except OSError:
        return "DOWN"


def _sandbox_status(image: str) -> str:
    docker = shutil.which("docker")
    if not docker:
        return "DOWN"
    try:
        completed = subprocess.run([docker, "image", "inspect", image], check=False, capture_output=True, timeout=3)
        return "UP" if completed.returncode == 0 else "DOWN"
    except (OSError, subprocess.SubprocessError):
        return "DOWN"


@app.get("/")
def root() -> dict[str, str]:
    """根路径提示。"""
    return {"service": "insighthub-agent-service", "docs": "/docs"}
