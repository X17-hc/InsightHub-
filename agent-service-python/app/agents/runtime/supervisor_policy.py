"""Supervisor 决策策略：查询接口，不发事件、不跳转。"""

from __future__ import annotations

from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.agents.runtime.names import (
    ANALYST,
    CRITIC,
    FINALIZE,
    KB_RESEARCHER,
    PLANNER,
    SUPPLEMENT,
    VERIFIER,
    WEB_RESEARCHER,
    WRITER,
)
from app.agents.runtime.ports import HandoffDecision
from app.agents.runtime.views import SupervisorView
from app.core.config import get_settings
from app.core.llm import get_chat_model

_SUPERVISOR_SYSTEM = """你是 InsightHub Supervisor。只能调用 transfer_to_* 工具，不要写报告或检索。
按研究进度选择下一个专家：无计划→create_plan；有待研究任务→knowledge_research 或 web_research；
研究刚结束→merge_evidence；然后 critic_review；SUPPLEMENT→supplement_research；
需要分析→data_analysis；最后 write_report 或 finalize。
"""


class SupervisorPolicy(Protocol):
    """根据视图决定下一步。"""

    def decide(self, view: SupervisorView) -> HandoffDecision:
        """返回目标节点与原因。"""


class MockSupervisorPolicy:
    """确定性路由，供单测与 AGENT_MOCK_LLM。"""

    def decide(self, view: SupervisorView) -> HandoffDecision:
        if not view.has_plan:
            return HandoffDecision(PLANNER, "plan is missing")
        if view.needs_verify:
            return HandoffDecision(VERIFIER, "research batch needs verification")
        if view.first_ready_type:
            target = KB_RESEARCHER if "knowledge" in view.first_ready_type else WEB_RESEARCHER
            return HandoffDecision(
                target,
                "execute next ready plan task",
                plan_task_id=view.first_ready_id,
            )
        if view.needs_critique or view.critic_verdict is None:
            return HandoffDecision(CRITIC, "evidence ready for critique")
        if view.critic_verdict == "SUPPLEMENT" and view.critic_round < view.max_critic_rounds:
            return HandoffDecision(SUPPLEMENT, "critic requested supplement")
        if view.enable_data_analysis and not view.has_analysis and not view.has_report:
            return HandoffDecision(ANALYST, "optional sandbox analysis")
        if not view.has_report:
            return HandoffDecision(WRITER, "write report from verified evidence")
        return HandoffDecision(FINALIZE, "mark task completed")


def constrain_supervisor_decision(view: SupervisorView, decision: HandoffDecision) -> HandoffDecision:
    """硬闸门覆盖 LLM 选点，避免批准后再次 create_plan / 研究后再派 web_research。

    无计划、有 ready 子任务、待核验时必须走 Mock 路由；其余阶段才采信 LLM。
    """
    required = MockSupervisorPolicy().decide(view)
    forced = (not view.has_plan) or view.needs_verify or bool(view.first_ready_type)
    if forced and decision.target != required.target:
        return required
    return decision


class LlmSupervisorPolicy:
    """LLM 主管：只 bind transfer 工具，非法目标由 HandoffPolicy 拦截。"""

    def __init__(self, transfer_names: tuple[str, ...]) -> None:
        self._transfer_names = transfer_names

    def decide(self, view: SupervisorView) -> HandoffDecision:
        settings = get_settings()
        if settings.agent_mock_llm or settings.synthetic_allowed():
            return MockSupervisorPolicy().decide(view)
        model = get_chat_model(temperature=0.0)
        prompt = (
            f"query={view.query}\nhas_plan={view.has_plan}\nneeds_verify={view.needs_verify}\n"
            f"ready_types={view.ready_types}\ncritic={view.critic_verdict}\n"
            f"has_report={view.has_report}\nallowed={self._transfer_names}"
        )
        response = model.invoke([SystemMessage(content=_SUPERVISOR_SYSTEM), HumanMessage(content=prompt)])
        text = str(getattr(response, "content", "") or "")
        # 只认 transfer_to_* ，禁止用裸节点名匹配：模型常回显 allowed=create_plan,web_research
        for name in self._transfer_names:
            if f"transfer_to_{name}" in text:
                ready_id = view.first_ready_id if name in {KB_RESEARCHER, WEB_RESEARCHER, SUPPLEMENT} else None
                return constrain_supervisor_decision(
                    view,
                    HandoffDecision(name, "llm supervisor selected target", plan_task_id=ready_id),
                )
        return MockSupervisorPolicy().decide(view)


def choose_supervisor_policy(*, enable_web_search: bool, enable_data_analysis: bool) -> SupervisorPolicy:
    """mock / 合成演示用确定性策略；否则 LLM。"""
    settings = get_settings()
    if settings.agent_mock_llm or settings.synthetic_allowed():
        return MockSupervisorPolicy()
    targets = [PLANNER, KB_RESEARCHER, VERIFIER, CRITIC, SUPPLEMENT, WRITER, FINALIZE]
    if enable_web_search:
        targets.append(WEB_RESEARCHER)
    if enable_data_analysis:
        targets.append(ANALYST)
    return LlmSupervisorPolicy(tuple(targets))
