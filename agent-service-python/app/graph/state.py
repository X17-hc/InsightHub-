"""LangGraph ResearchState 定义。

events / handoff_log 用 reducer 追加，避免节点并发覆盖。
Supervisor 只应通过 views.project_supervisor_view 读切片，不要把整份状态塞进 prompt。
"""

from __future__ import annotations

from typing import Annotated, Any, TypedDict


def _merge_events(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """合并事件列表（reducer）。"""
    return (left or []) + (right or [])


class ResearchState(TypedDict, total=False):
    """
    Supervisor + 专家共享状态。

    说明：events / handoff_log 使用 reducer 以便各节点追加。
    """

    task_id: str
    workspace_id: str
    user_id: str
    run_id: str
    trace_id: str
    user_query: str
    clarified_query: str | None
    plan: dict[str, Any] | None
    approved: bool
    pending_tasks: list[dict[str, Any]]
    completed_tasks: list[dict[str, Any]]
    evidence: list[dict[str, Any]]
    analysis_artifacts: list[dict[str, Any]]
    critique: dict[str, Any] | None
    quality: dict[str, Any] | None
    report: str | None
    step_count: int
    retry_count: int
    max_steps: int
    max_parallelism: int
    # 已完成的 Critic 轮次（每次 critic_review 结束后 +1）
    critic_round: int
    max_critic_rounds: int
    verified_evidence_ids: list[str]
    deadline_at: float
    enable_web_search: bool
    enable_data_analysis: bool
    knowledge_base_ids: list[str]
    citations: list[dict[str, Any]]
    errors: list[dict[str, Any]]
    status: str
    events: Annotated[list[dict[str, Any]], _merge_events]

    phase: str
    require_plan_approval: bool
    plan_revision: int
    revision_instruction: str | None
    plan_hash: str | None
    active_agent: str
    handoff_log: Annotated[list[dict[str, Any]], _merge_events]
    agent_inbox: dict[str, Any]
    needs_verify: bool
    needs_critique: bool
