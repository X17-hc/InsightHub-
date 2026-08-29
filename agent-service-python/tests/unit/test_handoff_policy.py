"""Handoff 白名单与循环限制。"""

import pytest

from app.agents.runtime.handoff import (
    HANDOFF_DENIED,
    HANDOFF_LIMIT,
    HandoffDenied,
    HandoffPolicy,
    default_allowlist,
)


def test_expert_cannot_handoff_to_another_expert() -> None:
    policy = HandoffPolicy(default_allowlist(enable_web_search=True, enable_data_analysis=False), max_handoffs=8)
    with pytest.raises(HandoffDenied) as exc:
        policy.allow("create_plan", "web_research")
    assert exc.value.code == HANDOFF_DENIED


def test_repeated_handoff_is_limited() -> None:
    policy = HandoffPolicy(default_allowlist(enable_web_search=True, enable_data_analysis=False), max_handoffs=3)
    log = [{"from": "supervisor", "to": "create_plan"}]
    with pytest.raises(HandoffDenied) as exc:
        policy.guard_history(log, "supervisor", "create_plan")
    assert exc.value.code == HANDOFF_LIMIT


def test_same_expert_next_plan_task_is_not_a_cycle() -> None:
    """两条 web 任务连续交给同一专家，子任务 id 变了必须放行。"""
    policy = HandoffPolicy(default_allowlist(enable_web_search=True, enable_data_analysis=False), max_handoffs=8)
    log = [{"from": "supervisor", "to": "web_research", "planTaskId": "task-1"}]
    policy.guard_history(log, "supervisor", "web_research", plan_task_id="task-2")


def test_same_expert_same_plan_task_is_a_cycle() -> None:
    policy = HandoffPolicy(default_allowlist(enable_web_search=True, enable_data_analysis=False), max_handoffs=8)
    log = [{"from": "supervisor", "to": "web_research", "planTaskId": "task-1"}]
    with pytest.raises(HandoffDenied) as exc:
        policy.guard_history(log, "supervisor", "web_research", plan_task_id="task-1")
    assert exc.value.code == HANDOFF_LIMIT


def _view(**overrides):
    from app.agents.runtime.views import SupervisorView

    data = {
        "has_plan": True,
        "plan_title": "t",
        "approved": True,
        "require_approval": True,
        "pending_count": 2,
        "completed_count": 0,
        "ready_types": ("web_research",),
        "first_ready_id": "task-1",
        "first_ready_type": "web_research",
        "needs_verify": False,
        "needs_critique": False,
        "critic_verdict": None,
        "critic_round": 0,
        "max_critic_rounds": 2,
        "enable_web_search": True,
        "enable_data_analysis": False,
        "has_report": False,
        "has_analysis": False,
        "query": "q",
        "verified_count": 0,
    }
    data.update(overrides)
    return SupervisorView(**data)


def test_constrain_blocks_replay_create_plan_after_approval() -> None:
    """批准后 LLM 回显 create_plan 必须被改写成下一条研究。"""
    from app.agents.runtime.ports import HandoffDecision
    from app.agents.runtime.supervisor_policy import constrain_supervisor_decision

    decision = constrain_supervisor_decision(
        _view(),
        HandoffDecision("create_plan", "llm echoed allowed names"),
    )
    assert decision.target == "web_research"
    assert decision.plan_task_id == "task-1"


def test_constrain_blocks_replay_web_research_when_verify_due() -> None:
    from app.agents.runtime.ports import HandoffDecision
    from app.agents.runtime.supervisor_policy import constrain_supervisor_decision

    decision = constrain_supervisor_decision(
        _view(first_ready_id=None, first_ready_type=None, ready_types=(), needs_verify=True, pending_count=0, completed_count=2),
        HandoffDecision("web_research", "llm selected research again"),
    )
    assert decision.target == "merge_evidence"


def test_handoff_count_limit() -> None:
    policy = HandoffPolicy(default_allowlist(enable_web_search=True, enable_data_analysis=False), max_handoffs=2)
    log = [{"from": "supervisor", "to": "create_plan"}, {"from": "supervisor", "to": "web_research"}]
    with pytest.raises(HandoffDenied) as exc:
        policy.guard_history(log, "supervisor", "critic_review")
    assert exc.value.code == HANDOFF_LIMIT
