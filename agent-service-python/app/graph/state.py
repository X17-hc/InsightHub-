"""LangGraph ResearchState 定义。"""

from __future__ import annotations

import operator
from typing import Annotated, Any, TypedDict


def _merge_events(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """合并事件列表（reducer）。"""
    return (left or []) + (right or [])


class ResearchState(TypedDict, total=False):
    """
    多智能体研究图共享状态。

    说明：events 使用 reducer 以便各节点追加事件。
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
