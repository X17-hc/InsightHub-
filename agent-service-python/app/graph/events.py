"""在图状态中追加协议事件的辅助函数。"""

from __future__ import annotations

from typing import Any

from app.schemas.protocol import utc_now_iso


def next_event_id(events: list[dict[str, Any]] | None) -> int:
    """根据已有事件计算下一个 eventId。"""
    if not events:
        return 1
    return max(int(e.get("eventId", 0)) for e in events) + 1


def make_event(
    *,
    events: list[dict[str, Any]] | None,
    task_id: str,
    run_id: str,
    event_type: str,
    node: str | None = None,
    data: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """构造一条符合协议的事件 dict（使用 camelCase 键，便于 Java 侧直接落库）。"""
    return {
        "eventId": next_event_id(events),
        "taskId": task_id,
        "runId": run_id,
        "node": node,
        "type": event_type,
        "timestamp": utc_now_iso(),
        "data": data or {},
    }
