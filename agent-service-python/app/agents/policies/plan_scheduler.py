"""计划 DAG 调度：只计算 ready / skip，不调用研究员。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

# 常量放在本模块，避免 policies ↔ runtime 循环导入
KB_TYPES = frozenset({"knowledge_research", "kb_research", "knowledge"})
WEB_TYPES = frozenset({"web_research", "web-research", "research"})


@dataclass(frozen=True)
class ReadyBatch:
    """一批可执行任务及因依赖失败被跳过的任务。"""

    ready: tuple[dict[str, Any], ...]
    skipped: tuple[dict[str, Any], ...]
    deadlock: bool


def seed_pending_from_plan(
    plan: dict[str, Any] | None,
    *,
    knowledge_base_ids: list[str],
    query: str,
) -> list[dict[str, Any]]:
    """从计划抽出研究任务；有 KB 时保证至少一条 knowledge_research。"""
    tasks = list((plan or {}).get("tasks") or [])
    pending = [
        item
        for item in tasks
        if str(item.get("type") or "").lower() in (KB_TYPES | WEB_TYPES)
    ]
    if knowledge_base_ids and not any(
        str(item.get("type") or "").lower() in KB_TYPES for item in pending
    ):
        pending.insert(
            0,
            {
                "id": "task-kb",
                "type": "knowledge_research",
                "description": query,
                "dependsOn": [],
            },
        )
    if not pending:
        pending = [
            {
                "id": "task-1",
                "type": "web_research",
                "description": query,
                "dependsOn": [],
            }
        ]
    return pending


def _status_sets(completed: list[dict[str, Any]]) -> tuple[set[str], set[str]]:
    done = {str(item.get("id")) for item in completed if item.get("status") == "DONE"}
    failed = {
        str(item.get("id"))
        for item in completed
        if item.get("status") in {"FAILED", "SKIPPED_DEPENDENCY_FAILED"}
    }
    return done, failed


def next_ready_batch(
    pending: list[dict[str, Any]],
    completed: list[dict[str, Any]],
    *,
    max_parallelism: int,
    type_filter: frozenset[str] | None = None,
) -> ReadyBatch:
    """返回当前可跑批次；无 ready 且仍有剩余则为死锁。"""
    done_ids, failed_ids = _status_sets(completed)
    remaining = [dict(item) for item in pending]
    skipped = [
        item
        for item in remaining
        if set(item.get("dependsOn") or ()) & failed_ids
    ]
    candidates = [
        item
        for item in remaining
        if item not in skipped
        and set(item.get("dependsOn") or ()) <= done_ids
        and (
            type_filter is None
            or str(item.get("type") or "").lower() in type_filter
        )
    ]
    limit = max(1, min(8, int(max_parallelism or 3)))
    ready = tuple(candidates[:limit])
    leftover = [item for item in remaining if item not in skipped]
    deadlock = type_filter is None and bool(leftover) and not ready
    return ReadyBatch(ready=ready, skipped=tuple(skipped), deadlock=deadlock)


def mark_skipped(item: dict[str, Any]) -> dict[str, Any]:
    """依赖失败后的跳过快照。"""
    return {**item, "status": "SKIPPED_DEPENDENCY_FAILED", "evidenceCount": 0}


def task_agent_name(task_type: str) -> str | None:
    """任务类型映射到图节点名。"""
    lowered = (task_type or "").lower()
    if lowered in KB_TYPES:
        return "knowledge_research"
    if lowered in WEB_TYPES:
        return "web_research"
    return None
