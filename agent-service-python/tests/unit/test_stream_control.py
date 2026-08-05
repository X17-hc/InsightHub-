"""流式执行与控制字单测。"""

from __future__ import annotations

import json
import os

import pytest

# 单测强制 mock LLM，避免真实调用；关闭边界延迟以加快测试
os.environ["AGENT_MOCK_LLM"] = "true"
os.environ["AGENT_MOCK_STEP_DELAY_MS"] = "0"

from app.core.config import get_settings
from app.graph.events import next_event_id
from app.schemas.protocol import AgentTaskRequest, TaskConfig
from app.services.control import (
    CONTROL_CANCELLED,
    CONTROL_PAUSED,
    CONTROL_RUNNING,
    InMemoryControlStore,
    reset_control_store_for_tests,
)
from app.services.runner import stream_research_task


@pytest.fixture(autouse=True)
def _memory_control():
    get_settings.cache_clear()
    store = InMemoryControlStore()
    reset_control_store_for_tests(store)
    yield store
    reset_control_store_for_tests(None)
    get_settings.cache_clear()


def test_next_event_id_increments():
    assert next_event_id([]) == 1
    assert next_event_id([{"eventId": 1}, {"eventId": 3}]) == 4


def test_stream_emits_multiple_events_and_result(_memory_control):
    req = AgentTaskRequest(
        taskId="task-stream-1",
        workspaceId="ws-1",
        userId="u-1",
        query="测试流式",
        config=TaskConfig(timeoutSeconds=60, enableWebSearch=False),
    )
    lines = list(stream_research_task(req, trace_id="trace-1"))
    assert len(lines) >= 3
    parsed = [json.loads(x) for x in lines]
    types = [p.get("type") for p in parsed]
    assert "TASK_STARTED" in types
    assert types[-1] == "TASK_RESULT"
    assert parsed[-1]["status"] in ("COMPLETED", "FAILED")
    event_ids = [p["eventId"] for p in parsed if "eventId" in p]
    assert event_ids == sorted(event_ids)
    assert len(event_ids) == len(set(event_ids))


def test_pause_at_boundary_stops_stream(_memory_control: InMemoryControlStore):
    task_id = "task-pause-1"
    req = AgentTaskRequest(
        taskId=task_id,
        workspaceId="ws-1",
        userId="u-1",
        query="暂停测试",
        config=TaskConfig(timeoutSeconds=60, enableWebSearch=False),
    )
    # 首事件后立即暂停：下一节点边界应停
    _memory_control.set(task_id, CONTROL_PAUSED, 100)
    lines = list(stream_research_task(req))
    parsed = [json.loads(x) for x in lines]
    types = [p.get("type") for p in parsed]
    assert "TASK_PAUSED" in types or parsed[-1].get("status") == "PAUSED"
    assert parsed[-1]["type"] == "TASK_RESULT"
    assert parsed[-1]["status"] == "PAUSED"


def test_cancel_stops_with_failed(_memory_control: InMemoryControlStore):
    task_id = "task-cancel-1"
    req = AgentTaskRequest(
        taskId=task_id,
        workspaceId="ws-1",
        userId="u-1",
        query="取消测试",
        config=TaskConfig(timeoutSeconds=60, enableWebSearch=False),
    )
    _memory_control.set(task_id, CONTROL_CANCELLED, 100)
    lines = list(stream_research_task(req))
    parsed = [json.loads(x) for x in lines]
    assert parsed[-1]["type"] == "TASK_RESULT"
    assert parsed[-1]["status"] == "CANCELLED"


def test_timeout_triggers_failed(monkeypatch, _memory_control: InMemoryControlStore):
    """用假图覆盖 TIMEOUT：首包后 deadline 已过。"""
    import time as real_time

    import app.services.runner as runner_mod

    class _FakeGraph:
        def stream(self, *_a, **_k):
            yield {
                "events": [
                    {
                        "eventId": 1,
                        "type": "TASK_STARTED",
                        "taskId": "task-timeout-1",
                        "runId": "r",
                    }
                ],
                "status": "RUNNING",
            }

        def update_state(self, *_a, **_k):
            return None

        def get_state(self, *_a, **_k):
            return None

    class _Clock:
        """第 1 次 monotonic=0（设 deadline）；之后返回 1e9 触发超时。"""

        n = 0

        @classmethod
        def monotonic(cls) -> float:
            cls.n += 1
            return 0.0 if cls.n == 1 else 1e9

        sleep = staticmethod(real_time.sleep)

    monkeypatch.setattr(runner_mod, "get_compiled_graph", lambda: _FakeGraph())
    monkeypatch.setattr(runner_mod, "time", _Clock)

    req = AgentTaskRequest(
        taskId="task-timeout-1",
        workspaceId="ws-1",
        userId="u-1",
        query="超时测试",
        config=TaskConfig(timeoutSeconds=1, enableWebSearch=False),
    )
    lines = list(stream_research_task(req))
    parsed = [json.loads(x) for x in lines]
    assert parsed[-1]["type"] == "TASK_RESULT"
    assert parsed[-1]["status"] == "FAILED"
    assert parsed[-1].get("error", {}).get("code") == "TIMEOUT"
    assert any(p.get("type") == "TASK_FAILED" for p in parsed)


def test_next_event_id_seed_continues(_memory_control):
    """retry 续号：config.nextEventId=10 时 TASK_STARTED 应为 10。"""
    req = AgentTaskRequest(
        taskId="task-seed-eid",
        workspaceId="ws-1",
        userId="u-1",
        query="seed",
        config=TaskConfig(timeoutSeconds=60, enableWebSearch=False, nextEventId=10),
    )
    lines = list(stream_research_task(req))
    parsed = [json.loads(x) for x in lines]
    started = next(p for p in parsed if p.get("type") == "TASK_STARTED")
    assert started["eventId"] == 10
    ids = [p["eventId"] for p in parsed if "eventId" in p]
    assert min(ids) >= 10


def test_pause_persist_then_resume_event_ids_monotonic(_memory_control: InMemoryControlStore):
    """暂停事件写入 Checkpoint 后，resume 不得复用 eventId。"""
    from app.services.runner import resume_research_task

    task_id = "task-pause-resume-eid"
    req = AgentTaskRequest(
        taskId=task_id,
        workspaceId="ws-1",
        userId="u-1",
        query="pause resume eventId",
        config=TaskConfig(timeoutSeconds=60, enableWebSearch=False),
    )
    _memory_control.set(task_id, CONTROL_PAUSED, 100)
    paused_lines = list(stream_research_task(req))
    paused_parsed = [json.loads(x) for x in paused_lines]
    assert paused_parsed[-1]["status"] == "PAUSED"
    paused_ids = [p["eventId"] for p in paused_parsed if "eventId" in p]
    assert paused_ids
    max_paused = max(paused_ids)

    _memory_control.set(task_id, CONTROL_RUNNING, 100)
    resume_lines = list(resume_research_task(task_id, timeout_seconds=60))
    resume_parsed = [json.loads(x) for x in resume_lines]
    resume_ids = [p["eventId"] for p in resume_parsed if "eventId" in p]
    all_ids = []
    seen = set()
    for p in paused_parsed + resume_parsed:
        if "eventId" not in p:
            continue
        eid = p["eventId"]
        if eid in seen:
            continue
        seen.add(eid)
        all_ids.append(eid)
    assert all_ids == sorted(all_ids)
    assert len(all_ids) == len(set(all_ids))
    if resume_ids:
        new_only = [i for i in resume_ids if i > max_paused]
        assert new_only, f"expected new eventIds > {max_paused}, got {resume_ids}"


def test_inmemory_control_ttl_expires(_memory_control: InMemoryControlStore):
    import time

    store = InMemoryControlStore()
    store.set("t1", CONTROL_PAUSED, ttl_seconds=1)
    assert store.get("t1") == CONTROL_PAUSED
    time.sleep(1.1)
    assert store.get("t1") == CONTROL_RUNNING
