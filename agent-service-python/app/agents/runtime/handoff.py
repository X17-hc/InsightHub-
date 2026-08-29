"""Handoff 鉴权与事件。专家互跳在这里被拒绝，不依赖模型自觉。"""

from __future__ import annotations

from typing import Any

from langchain_core.tools import tool
from langgraph.types import Command

from app.agents.runtime.names import HANDOFF_LIMIT_MULTIPLIER, SUPERVISOR
from app.agents.runtime.ports import HandoffDecision
from app.graph.events import make_event
from app.graph.state import ResearchState

HANDOFF_DENIED = "HANDOFF_DENIED"
HANDOFF_LIMIT = "HANDOFF_LIMIT"


class HandoffDenied(ValueError):
    """非法或超限跳转。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class HandoffPolicy:
    """校验 from→to，并限制跳转次数 / 连续重复。"""

    def __init__(self, allowlist: dict[str, frozenset[str]], *, max_handoffs: int) -> None:
        self._allowlist = allowlist
        self._max_handoffs = max(1, max_handoffs)

    def allow(self, source: str, target: str) -> None:
        """目标不在白名单则拒绝。"""
        allowed = self._allowlist.get(source, frozenset())
        if target not in allowed:
            raise HandoffDenied(
                HANDOFF_DENIED,
                f"handoff {source} -> {target} is not allowed",
            )

    def guard_history(
        self,
        log: list[dict[str, Any]],
        source: str,
        target: str,
        *,
        plan_task_id: str | None = None,
    ) -> None:
        """次数上限，以及「同一专家 + 同一子任务」的原地环。

        多条同类型计划任务会连续 supervisor→web_research；子任务 id 变了算前进，不是环。
        """
        if len(log) >= self._max_handoffs:
            raise HandoffDenied(HANDOFF_LIMIT, "handoff limit exceeded")
        if not log:
            return
        last = log[-1]
        if last.get("from") != source or last.get("to") != target:
            return
        last_task = str(last.get("planTaskId") or "") or None
        current_task = str(plan_task_id or "") or None
        # 两边都有 id 且不同：下一批同专家任务，放行
        if last_task and current_task and last_task != current_task:
            return
        raise HandoffDenied(HANDOFF_LIMIT, "repeated handoff cycle")


def default_allowlist(*, enable_web_search: bool, enable_data_analysis: bool) -> dict[str, frozenset[str]]:
    """主管可去专家；专家只能回主管。"""
    # web_research 节点在 enable_web_search=false 时仍可走合成/失败路径，路由不应裁掉。
    del enable_web_search
    supervisor_targets = {
        "create_plan",
        "knowledge_research",
        "web_research",
        "merge_evidence",
        "critic_review",
        "supplement_research",
        "write_report",
        "finalize",
    }
    if enable_data_analysis:
        supervisor_targets.add("data_analysis")
    experts = {
        "create_plan",
        "knowledge_research",
        "web_research",
        "merge_evidence",
        "critic_review",
        "supplement_research",
        "data_analysis",
        "write_report",
        "finalize",
    }
    allowlist = {SUPERVISOR: frozenset(supervisor_targets)}
    for name in experts:
        allowlist[name] = frozenset({SUPERVISOR})
    return allowlist


def max_handoffs_for(state: ResearchState) -> int:
    """handoff 上限随任务步数预算放大。"""
    raw = state.get("max_steps")
    max_steps = 20 if raw is None else int(raw)
    return max(4, max_steps * HANDOFF_LIMIT_MULTIPLIER)


def emit_handoff(state: ResearchState, decision: HandoffDecision, *, source: str) -> dict[str, Any]:
    """构造 AGENT_HANDOFF 事件（命令侧，不改变路由决策）。"""
    events = list(state.get("events") or [])
    return make_event(
        events=events,
        task_id=state["task_id"],
        run_id=state["run_id"],
        event_type="AGENT_HANDOFF",
        node=source,
        data={
            "from": source,
            "to": decision.target,
            "reason": decision.reason,
            "planTaskId": decision.plan_task_id,
        },
    )


def make_handoff_tool(target: str, *, source: str, policy: HandoffPolicy):
    """给 LLM Supervisor 的 transfer 工具；真正 goto 仍由节点层执行。"""

    @tool(f"transfer_to_{target}")
    def transfer(reason: str = "") -> str:
        """把控制权交给指定专家或主管。"""
        policy.allow(source, target)
        return f"handoff:{target}:{reason}"

    transfer.name = f"transfer_to_{target}"
    return transfer


def command_to(target: str, update: dict[str, Any]) -> Command:
    """handoff 的唯一跳转载体。"""
    return Command(goto=target, update=update)
