"""LangGraph ResearchState 定义。

events / handoff_log 用 reducer 追加，避免节点并发覆盖。
Supervisor 只应通过 views.project_supervisor_view 读切片，不要把整份状态塞进 prompt。
"""

from __future__ import annotations

from typing import Annotated, Any, TypedDict


def _merge_events(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """追加 reducer；不负责 eventId 去重，唯一性由事件生成/持久化层保证。"""
    return (left or []) + (right or [])


class ResearchState(TypedDict, total=False):
    """
    Supervisor + 专家共享状态。

    身份、审批哈希和 deadline 在一次 run 中不可变；节点只能返回自己负责的
    状态增量。events / handoff_log 使用追加 reducer，其他集合若要追加必须
    显式带回完整值。State 不得保存密钥、完整网页正文或分析脚本正文。
    """

    # 执行身份：checkpoint 恢复后必须与原 taskId/workspaceId/runId 保持一致。
    task_id: str
    workspace_id: str
    user_id: str
    run_id: str
    trace_id: str
    # 用户输入与计划：plan_hash 将人工审批绑定到确切版本。
    user_query: str
    clarified_query: str | None
    plan: dict[str, Any] | None
    approved: bool
    # DAG 投影：pending/completed 的状态变更由 Supervisor 统一协调。
    pending_tasks: list[dict[str, Any]]
    completed_tasks: list[dict[str, Any]]
    # 研究结果：evidence 可含候选项，Writer 只能消费已核验证据。
    evidence: list[dict[str, Any]]
    analysis_artifacts: list[dict[str, Any]]
    critique: dict[str, Any] | None
    quality: dict[str, Any] | None
    report: str | None
    # 有界执行控制：step、deadline 与 Critic 轮次共同保证流程可终止。
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
    # 协议事件仅追加；不得携带秘密、完整页面或宿主机绝对路径。
    events: Annotated[list[dict[str, Any]], _merge_events]

    # HITL 审批状态。
    phase: str
    require_plan_approval: bool
    plan_revision: int
    revision_instruction: str | None
    plan_hash: str | None
    # Supervisor handoff 状态：日志仅保存安全摘要，inbox 是专家间受控输入。
    active_agent: str
    handoff_log: Annotated[list[dict[str, Any]], _merge_events]
    agent_inbox: dict[str, Any]
    needs_verify: bool
    needs_critique: bool
