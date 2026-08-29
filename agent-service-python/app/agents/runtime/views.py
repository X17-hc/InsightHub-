"""把 ResearchState 投影成主管可见切片。

整份 evidence/report 不得进入 Supervisor prompt，避免上下文污染与泄密。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.graph.state import ResearchState

_QUERY_CAP = 400
_TITLE_CAP = 120


@dataclass(frozen=True)
class SupervisorView:
    """主管决策输入：字段白名单。"""

    has_plan: bool
    plan_title: str
    approved: bool
    require_approval: bool
    pending_count: int
    completed_count: int
    ready_types: tuple[str, ...]
    first_ready_id: str | None
    first_ready_type: str | None
    needs_verify: bool
    needs_critique: bool
    critic_verdict: str | None
    critic_round: int
    max_critic_rounds: int
    enable_web_search: bool
    enable_data_analysis: bool
    has_report: bool
    has_analysis: bool
    query: str
    verified_count: int


def project_supervisor_view(state: ResearchState, ready_tasks: list[dict[str, Any]]) -> SupervisorView:
    """从状态与已算好的 ready 列表构造视图。"""
    plan = state.get("plan") or {}
    critique = state.get("critique") or {}
    first = ready_tasks[0] if ready_tasks else None
    ready_types = tuple(
        dict.fromkeys(str(item.get("type") or "") for item in ready_tasks if item.get("type"))
    )
    query = str(state.get("clarified_query") or state.get("user_query") or "")
    evidence = list(state.get("evidence") or [])
    return SupervisorView(
        has_plan=bool(plan),
        plan_title=str(plan.get("title") or "")[:_TITLE_CAP],
        approved=bool(state.get("approved")),
        require_approval=bool(state.get("require_plan_approval")),
        pending_count=len(state.get("pending_tasks") or []),
        completed_count=len(state.get("completed_tasks") or []),
        ready_types=ready_types,
        first_ready_id=str(first.get("id")) if first else None,
        first_ready_type=str(first.get("type")) if first else None,
        needs_verify=bool(state.get("needs_verify")),
        needs_critique=bool(state.get("needs_critique")),
        critic_verdict=critique.get("verdict"),
        critic_round=int(state.get("critic_round") or 0),
        max_critic_rounds=int(state.get("max_critic_rounds") or 2),
        enable_web_search=bool(state.get("enable_web_search", True)),
        enable_data_analysis=bool(state.get("enable_data_analysis")),
        has_report=bool(state.get("report")),
        has_analysis=bool(state.get("analysis_artifacts")),
        query=query[:_QUERY_CAP],
        verified_count=sum(1 for item in evidence if item.get("verified")),
    )
