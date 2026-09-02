"""LangGraph Checkpoint 相关的小型适配层。"""

from __future__ import annotations

from typing import Any

from app.graph.builder import delete_thread_checkpoint


def checkpoint_thread_id(task_id: str, run_id: str) -> str:
    """构造 checkpoint 隔离键；runId 防止重试轮次复用旧图状态。"""
    return f"{task_id}:{run_id}"


def checkpoint_values(graph: Any, task_id: str, run_id: str) -> dict[str, Any]:
    """读取任务 checkpoint，不把 LangGraph 细节泄漏到 API 层。"""
    snapshot = graph.get_state({"configurable": {"thread_id": checkpoint_thread_id(task_id, run_id)}})
    return dict((snapshot.values if snapshot is not None else None) or {})


def reset_checkpoint(task_id: str, run_id: str) -> None:
    """删除指定运行轮次的旧 checkpoint。

    仅完整重试可调用；pause/resume 和计划审批恢复必须保留原 checkpoint，
    否则 interrupt 位置、计划 hash 与已产生事件会丢失。
    """
    delete_thread_checkpoint(checkpoint_thread_id(task_id, run_id))


def patch_control_event(
    graph: Any,
    config: dict[str, Any],
    event: dict[str, Any],
    *,
    status: str | None = None,
) -> None:
    """将 pause/cancel 等控制事件追加到 checkpoint。

    ``events`` 依赖 ResearchState reducer 追加，不能用完整历史覆盖；该补丁只
    持久化控制投影，不负责向 Java 分配 eventNo。
    """
    patch: dict[str, Any] = {"events": [event]}
    if status is not None:
        patch["status"] = status
    graph.update_state(config, patch)
