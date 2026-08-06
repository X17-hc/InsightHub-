"""FastAPI 入口。"""

from __future__ import annotations

from dotenv import load_dotenv
from fastapi import FastAPI

from app.api.knowledge import router as knowledge_router
from app.api.tasks import router as tasks_router
from app.core.config import REPO_ROOT

# 加载仓库根目录 .env
load_dotenv(REPO_ROOT / ".env")
load_dotenv()

app = FastAPI(title="InsightHub Agent Service", version="0.1.0")
app.include_router(tasks_router)
app.include_router(knowledge_router)


@app.get("/")
def root() -> dict[str, str]:
    """根路径提示。"""
    return {"service": "insighthub-agent-service", "docs": "/docs"}
