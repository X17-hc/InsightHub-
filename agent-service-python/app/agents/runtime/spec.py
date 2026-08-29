"""Agent 注册描述：prompt / 工具 / 允许的跳转，不含执行循环。"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

from langchain_core.tools import BaseTool

from app.agents.runtime.names import DEFAULT_MAX_REACT_ITERS, SUPERVISOR
from app.graph.state import ResearchState


@dataclass(frozen=True)
class AgentSpec:
    """一个可注册专家。新增角色只加 spec，不改 builder 硬编码。"""

    name: str
    description: str
    system_prompt: str = ""
    tools: Sequence[BaseTool] = ()
    allowed_handoffs: frozenset[str] = field(default_factory=lambda: frozenset({SUPERVISOR}))
    max_react_iters: int = DEFAULT_MAX_REACT_ITERS
    output_fields: frozenset[str] = field(default_factory=frozenset)
    mock_run: Callable[[ResearchState], dict[str, Any]] | None = None
