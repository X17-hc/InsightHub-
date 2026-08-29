"""Supervisor + Handoff 运行时。

本包不 import rag / web 工具 / runner：框架只认端口与 AgentSpec。
"""

from app.agents.runtime.handoff import HandoffDenied, HandoffPolicy
from app.agents.runtime.ports import HandoffDecision, ToolContext
from app.agents.runtime.spec import AgentSpec

__all__ = [
    "AgentSpec",
    "HandoffDecision",
    "HandoffDenied",
    "HandoffPolicy",
    "ToolContext",
]
