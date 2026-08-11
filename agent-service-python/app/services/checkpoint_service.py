"""LangGraph Checkpoint 相关的小型适配层。"""

from __future__ import annotations

from typing import Any

from app.graph.builder import delete_thread_checkpoint


def checkpoint_values(graph: Any, task_id: str) -> dict[str, Any]:
    """读取任务 checkpoint，不把 LangGraph 细节泄漏到 API 层。"""
    snapshot = graph.get_state({"configurable": {"thread_id": task_id}})
    return dict((snapshot.values if snapshot is not None else None) or {})


def reset_checkpoint(task_id: str) -> None:
    """删除完整重试使用的旧 checkpoint。"""
    delete_thread_checkpoint(task_id)


def patch_control_event(
    graph: Any,
    config: dict[str, Any],
    event: dict[str, Any],
    *,
    status: str | None = None,
) -> None:
    """将控制事件追加到 checkpoint。"""
    patch: dict[str, Any] = {"events": [event]}
    if status is not None:
        patch["status"] = status
    graph.update_state(config, patch)
