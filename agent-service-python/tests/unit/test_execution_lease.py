"""单任务执行租约回归测试。"""

from __future__ import annotations

import json

from app.schemas.protocol import AgentTaskRequest, TaskConfig
from app.services.execution_lease import (
    InMemoryExecutionLeaseStore,
    reset_execution_lease_store_for_tests,
)
from app.services.runner import stream_research_task


def test_existing_task_lease_rejects_second_execution():
    store = InMemoryExecutionLeaseStore()
    assert store.acquire("task-lease-conflict", "holder", 60, 0)
    reset_execution_lease_store_for_tests(store)
    req = AgentTaskRequest(
        taskId="task-lease-conflict",
        workspaceId="ws-1",
        userId="u-1",
        query="租约冲突",
        config=TaskConfig(timeoutSeconds=60, enableWebSearch=False),
    )

    parsed = [json.loads(line) for line in stream_research_task(req)]

    assert parsed[-1]["type"] == "TASK_RESULT"
    assert parsed[-1]["status"] == "FAILED"
    assert parsed[-1]["error"]["code"] == "TASK_ALREADY_RUNNING"
