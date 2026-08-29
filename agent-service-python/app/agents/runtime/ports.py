"""框架端口：工具上下文、事件出口、handoff 决策。

副作用只通过这些端口离开 runtime，专家业务代码不应直接碰 runner。
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Protocol


class EventSink(Protocol):
    """可选实时推送（LangGraph custom stream）。"""

    def emit(self, event: dict[str, Any]) -> None:
        """推送一条协议事件；无 writer 时允许静默。"""


@dataclass(frozen=True)
class ToolContext:
    """注入给工具的任务切片，避免工具读取整份图状态。"""

    task_id: str
    run_id: str
    workspace_id: str
    knowledge_base_ids: tuple[str, ...] = ()
    deadline_at: float = 0.0
    emit: Callable[[dict[str, Any]], None] | None = None


@dataclass(frozen=True)
class HandoffDecision:
    """Supervisor 的查询结果：下一步去哪，以及原因。不负责发事件。"""

    target: str
    reason: str
    plan_task_id: str | None = None
    state_patch: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ReactTurn:
    """ReAct 单轮预算，避免往循环里塞一串散参数。"""

    iteration: int
    max_iters: int
    node: str
