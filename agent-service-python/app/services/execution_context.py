"""任务执行上下文：集中管理运行标识和截止时间。"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass

from app.schemas.protocol import AgentTaskRequest


@dataclass(frozen=True, slots=True)
class ExecutionContext:
    task_id: str
    run_id: str
    trace_id: str
    timeout_seconds: int
    deadline_at: float

    @classmethod
    def create(cls, request: AgentTaskRequest, trace_id: str | None = None) -> "ExecutionContext":
        timeout = max(1, int(request.config.timeout_seconds or 300))
        return cls(
            task_id=request.task_id,
            run_id=request.run_id or f"run-{uuid.uuid4().hex[:12]}",
            trace_id=trace_id or f"trace-{uuid.uuid4().hex[:12]}",
            timeout_seconds=timeout,
            deadline_at=time.time() + timeout,
        )

    @classmethod
    def for_resume(
        cls,
        task_id: str,
        *,
        run_id: str | None = None,
        trace_id: str | None = None,
        timeout_seconds: int = 300,
    ) -> "ExecutionContext":
        timeout = max(1, int(timeout_seconds))
        return cls(
            task_id=task_id,
            run_id=run_id or f"run-{uuid.uuid4().hex[:12]}",
            trace_id=trace_id or f"trace-{uuid.uuid4().hex[:12]}",
            timeout_seconds=timeout,
            deadline_at=time.time() + timeout,
        )

    def expired(self) -> bool:
        return time.time() > self.deadline_at
