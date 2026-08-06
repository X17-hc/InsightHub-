"""与 docs/protocol.md 对齐的请求 / 事件 / 响应模型。"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, Field


class TaskConfig(BaseModel):
    """任务级执行配置。"""

    max_steps: int = Field(default=20, alias="maxSteps")
    max_parallelism: int = Field(default=3, alias="maxParallelism")
    require_plan_approval: bool = Field(default=False, alias="requirePlanApproval")
    enable_web_search: bool = Field(default=True, alias="enableWebSearch")
    # 流式执行超时（秒），超时 yield TASK_FAILED(TIMEOUT)
    timeout_seconds: int = Field(default=300, alias="timeoutSeconds")
    # 下一个可用 eventId（Java 传 DB max+1）；用于 retry 续号
    next_event_id: int | None = Field(default=None, alias="nextEventId")

    model_config = {"populate_by_name": True}


class ResumeTaskRequest(BaseModel):
    """从 Checkpoint 恢复流式执行。"""

    run_id: str | None = Field(default=None, alias="runId")
    trace_id: str | None = Field(default=None, alias="traceId")

    model_config = {"populate_by_name": True}


class AgentTaskRequest(BaseModel):
    """创建 Agent 任务请求体。"""

    task_id: str = Field(alias="taskId")
    workspace_id: str = Field(alias="workspaceId")
    user_id: str = Field(alias="userId")
    query: str
    knowledge_base_ids: list[str] = Field(default_factory=list, alias="knowledgeBaseIds")
    config: TaskConfig = Field(default_factory=TaskConfig)

    model_config = {"populate_by_name": True}


class AgentEvent(BaseModel):
    """节点事件。"""

    event_id: int = Field(alias="eventId")
    task_id: str = Field(alias="taskId")
    run_id: str = Field(alias="runId")
    node: str | None = None
    type: str
    timestamp: str
    data: dict[str, Any] = Field(default_factory=dict)

    model_config = {"populate_by_name": True}


class AgentError(BaseModel):
    """结构化错误。"""

    code: str
    message: str
    trace_id: str | None = Field(default=None, alias="traceId")
    details: dict[str, Any] = Field(default_factory=dict)

    model_config = {"populate_by_name": True}


class AgentTaskResponse(BaseModel):
    """同步任务响应。"""

    task_id: str = Field(alias="taskId")
    run_id: str = Field(alias="runId")
    status: Literal["COMPLETED", "FAILED"]
    report_markdown: str | None = Field(default=None, alias="reportMarkdown")
    events: list[AgentEvent] = Field(default_factory=list)
    citations: list[dict[str, Any]] = Field(default_factory=list)
    error: AgentError | None = None

    model_config = {"populate_by_name": True}


def utc_now_iso() -> str:
    """返回 UTC ISO8601 时间戳。"""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
