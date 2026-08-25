"""与 docs/protocol.md 对齐的请求 / 事件 / 响应模型。"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


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
    # Critic 最多轮次：1=仅评审不补充；2=允许一轮 SUPPLEMENT 后再评
    max_critic_rounds: int = Field(default=2, ge=1, le=2, alias="maxCriticRounds")
    # Day4 数据分析开关；Day3 固定透传 false
    enable_data_analysis: bool = Field(default=False, alias="enableDataAnalysis")
    # 流式执行超时（秒），超时 yield TASK_FAILED(TIMEOUT)
    timeout_seconds: int = Field(default=900, alias="timeoutSeconds")
    # 下一个可用 eventId（Java 传 DB max+1）；用于 retry 续号
    next_event_id: int | None = Field(default=None, alias="nextEventId")


class PlanTask(StrictModel):
    id: str = Field(min_length=1, max_length=64)
    type: Literal["web_research", "knowledge_research"]
    description: str = Field(min_length=1, max_length=2000)
    depends_on: tuple[str, ...] = Field(default=(), alias="dependsOn")


class SourceRequirements(StrictModel):
    min_verified_sources: int = Field(default=3, ge=3, le=20, alias="minVerifiedSources")
    require_official_sources: bool = Field(default=True, alias="requireOfficialSources")


class Plan(StrictModel):
    title: str = Field(min_length=1, max_length=256)
    objective: str = Field(min_length=1, max_length=4000)
    research_dimensions: tuple[str, ...] = Field(default=(), alias="researchDimensions", max_length=8)
    source_requirements: SourceRequirements = Field(default_factory=SourceRequirements, alias="sourceRequirements")
    tasks: tuple[PlanTask, ...] = Field(min_length=1, max_length=8)

    @field_validator("tasks")
    @classmethod
    def unique_task_ids(cls, value: tuple[PlanTask, ...]) -> tuple[PlanTask, ...]:
        ids = [item.id for item in value]
        if len(ids) != len(set(ids)):
            raise ValueError("plan task ids must be unique")
        return value

    @model_validator(mode="after")
    def validate_dag(self) -> "Plan":
        ids = {task.id for task in self.tasks}
        graph = {task.id: set(task.depends_on) for task in self.tasks}
        for task_id, dependencies in graph.items():
            if task_id in dependencies:
                raise ValueError("plan task cannot depend on itself")
            missing = dependencies - ids
            if missing:
                raise ValueError(f"plan task references missing dependencies: {sorted(missing)}")
        visiting: set[str] = set()
        visited: set[str] = set()

        def visit(task_id: str) -> None:
            if task_id in visiting:
                raise ValueError("plan dependency graph contains a cycle")
            if task_id in visited:
                return
            visiting.add(task_id)
            for dependency in graph[task_id]:
                visit(dependency)
            visiting.remove(task_id)
            visited.add(task_id)

        for task_id in graph:
            visit(task_id)
        return self


class Evidence(StrictModel):
    """研究证据快照（不可变契约）。"""

    id: str = Field(min_length=1, max_length=128)
    source_title: str = Field(alias="sourceTitle", min_length=1, max_length=512)
    source_uri: str = Field(default="", alias="sourceUri", max_length=2048)
    canonical_uri: str | None = Field(default=None, alias="canonicalUri", max_length=2048)
    final_uri: str | None = Field(default=None, alias="finalUri", max_length=2048)
    quoted_text: str = Field(default="", alias="quotedText", max_length=8000)
    source_type: str = Field(default="WEB", alias="sourceType", max_length=32)
    document_id: str | None = Field(default=None, alias="documentId")
    chunk_id: str | None = Field(default=None, alias="chunkId")
    verified: bool = False
    verification_status: Literal["VERIFIED", "CANDIDATE", "SYNTHETIC"] = Field(default="CANDIDATE", alias="verificationStatus")
    verification_reason: str | None = Field(default=None, alias="verificationReason", max_length=512)
    retrieved_at: str | None = Field(default=None, alias="retrievedAt")
    content_hash: str | None = Field(default=None, alias="contentHash", max_length=64)
    http_status: int | None = Field(default=None, alias="httpStatus", ge=100, le=599)


class SupplementTask(StrictModel):
    """Critic 请求的补充研究子任务（最多 2 个）。"""

    id: str = Field(min_length=1, max_length=64)
    type: Literal["web_research", "knowledge_research"]
    description: str = Field(min_length=1, max_length=2000)


class CritiqueResult(StrictModel):
    """Critic 评审结果：PASS / SUPPLEMENT / FAIL。"""

    verdict: Literal["PASS", "SUPPLEMENT", "FAIL"]
    summary: str = Field(default="", max_length=4000)
    gaps: tuple[str, ...] = Field(default=())
    limitations: tuple[str, ...] = Field(default=())
    supplement_tasks: tuple[SupplementTask, ...] = Field(
        default=(), alias="supplementTasks", max_length=2
    )

    @field_validator("supplement_tasks")
    @classmethod
    def limit_supplement_tasks(
        cls, value: tuple[SupplementTask, ...]
    ) -> tuple[SupplementTask, ...]:
        if len(value) > 2:
            raise ValueError("at most 2 supplement tasks")
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
    quality: dict[str, Any] | None = None
    error: AgentError | None = None



def utc_now_iso() -> str:
    """返回 UTC ISO8601 时间戳。"""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
