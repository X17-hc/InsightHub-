"""与 docs/protocol.md 对齐的请求 / 事件 / 响应模型。"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(
        frozen = True,
        extra = "forbid",
        populate_by_name = True,
    )


class TaskConfig(StrictModel):
    """任务级执行配置。"""

    max_steps: int = Field(default=20, ge=1, alias="maxSteps")
    max_parallelism: int = Field(default=3, alias="maxParallelism")
    require_plan_approval: bool = Field(default=False, alias="requirePlanApproval")
    enable_web_search: bool = Field(default=True, alias="enableWebSearch")
    # 流式执行超时（秒），超时 yield TASK_FAILED(TIMEOUT)
    timeout_seconds: int = Field(default=300, alias="timeoutSeconds")
    # 下一个可用 eventId（Java 传 DB max+1）；用于 retry 续号
    next_event_id: int | None = Field(default=None, alias="nextEventId")


class PlanTask(StrictModel):
    id: str = Field(min_length=1, max_length=64)
    type: Literal["web_research", "knowledge_research"]
    description: str = Field(min_length=1, max_length=2000)
    depends_on: tuple[str, ...] = Field(default=(), alias="dependsOn")


class Plan(StrictModel):
    title: str = Field(min_length=1, max_length=256)
    objective: str = Field(min_length=1, max_length=4000)
    tasks: tuple[PlanTask, ...] = Field(min_length=1, max_length=3)

    @field_validator("tasks")
    @classmethod
    def unique_task_ids(cls, value: tuple[PlanTask, ...]) -> tuple[PlanTask, ...]:
        ids = [item.id for item in value]
        if len(ids) != len(set(ids)):
            raise ValueError("plan task ids must be unique")
        return value


class ResumeTaskRequest(StrictModel):
    """从 Checkpoint 恢复流式执行。"""

    run_id: str | None = Field(default=None, alias="runId")
    trace_id: str | None = Field(default=None, alias="traceId")

    approved_plan_hash: str | None = Field(default=None, alias="approvedPlanHash")


class PlanApprovalResumeRequest(StrictModel):
    run_id: str = Field(alias="runId", min_length=1, max_length=64)
    approved_plan_hash: str = Field(alias="approvedPlanHash", min_length=64, max_length=64)


class AgentTaskRequest(StrictModel):
    """创建 Agent 任务请求体。"""

    task_id: str = Field(alias="taskId", min_length=1, max_length=64)
    workspace_id: str = Field(alias="workspaceId", min_length=1, max_length=64)
    user_id: str = Field(alias="userId", min_length=1, max_length=64)
    query: str = Field(min_length=1, max_length=20000)
    phase: Literal["PLAN", "EXECUTE"] = "PLAN"
    run_id: str | None = Field(default=None, alias="runId")
    plan_revision: int | None = Field(default=None, ge=1, alias="planRevision")
    revision_instruction: str | None = Field(default=None, max_length=2000, alias="revisionInstruction")
    approved_plan_hash: str | None = Field(default=None, alias="approvedPlanHash")
    knowledge_base_ids: tuple[str, ...] = Field(default=(), alias="knowledgeBaseIds")
    config: TaskConfig = Field(default_factory=TaskConfig)


class AgentEvent(StrictModel):
    """节点事件。"""

    schema_version: str = Field(default="1.0", alias="schemaVersion")
    event_id: int = Field(alias="eventId", ge=1)
    task_id: str = Field(alias="taskId")
    run_id: str = Field(alias="runId")
    node: str | None = None
    type: str
    timestamp: str
    data: dict[str, Any] = Field(default_factory=dict)


class AgentError(StrictModel):
    """结构化错误。"""

    code: str
    message: str
    trace_id: str | None = Field(default=None, alias="traceId")
    details: dict[str, Any] = Field(default_factory=dict)


class AgentTaskResponse(BaseModel):
    """同步任务响应。"""

    task_id: str = Field(alias="taskId")
    run_id: str = Field(alias="runId")
    status: Literal["WAITING_APPROVAL", "COMPLETED", "FAILED"]

    plan_revision: int | None = Field(default=None, alias="planRevision")
    plan_hash: str | None = Field(default=None, alias="planHash")
    plan: Plan | None = None

    report_markdown: str | None = Field(default=None, alias="reportMarkdown")
    events: tuple[AgentEvent, ...] = ()
    citations: tuple[dict[str, Any], ...] = ()
    error: AgentError | None = None



def utc_now_iso() -> str:
    """返回 UTC ISO8601 时间戳。"""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
