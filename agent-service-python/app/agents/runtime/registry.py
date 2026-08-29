"""专家注册表：构图时按开关裁剪节点，不在 builder 里写角色 if/else。"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from app.agents.policies.plan_scheduler import next_ready_batch
from app.agents.runtime.names import (
    ANALYST,
    CRITIC,
    FINALIZE,
    KB_RESEARCHER,
    PLANNER,
    SUPERVISOR,
    SUPPLEMENT,
    VERIFIER,
    WEB_RESEARCHER,
    WRITER,
)
from app.agents.runtime.spec import AgentSpec
from app.graph.state import ResearchState


def wrap_research(node_fn: Callable[[ResearchState], dict[str, Any]]) -> Callable[[ResearchState], dict[str, Any]]:
    """仅当没有更多 ready 研究任务时打开核验闸门。"""

    def wrapped(state: ResearchState) -> dict[str, Any]:
        result = node_fn(state)
        if result.get("status") == "FAILED":
            return result
        pending = list(result.get("pending_tasks") if "pending_tasks" in result else state.get("pending_tasks") or [])
        completed = list(result.get("completed_tasks") if "completed_tasks" in result else state.get("completed_tasks") or [])
        batch = next_ready_batch(
            pending,
            completed,
            max_parallelism=int(result.get("max_parallelism") or state.get("max_parallelism") or 3),
        )
        return {**result, "needs_verify": not batch.ready}

    return wrapped


def wrap_verifier(node_fn: Callable[[ResearchState], dict[str, Any]]) -> Callable[[ResearchState], dict[str, Any]]:
    """核验后打开评审闸门。"""

    def wrapped(state: ResearchState) -> dict[str, Any]:
        result = node_fn(state)
        if result.get("status") == "FAILED":
            return result
        return {**result, "needs_verify": False, "needs_critique": True}

    return wrapped


def wrap_critic(node_fn: Callable[[ResearchState], dict[str, Any]]) -> Callable[[ResearchState], dict[str, Any]]:
    """评审完成后关闭 needs_critique。"""

    def wrapped(state: ResearchState) -> dict[str, Any]:
        result = node_fn(state)
        if result.get("status") == "FAILED":
            return result
        return {**result, "needs_critique": False}

    return wrapped


def build_agent_specs() -> dict[str, AgentSpec]:
    """描述各专家职责，供文档与 LLM Supervisor when-to-use。"""
    return {
        PLANNER: AgentSpec(
            name=PLANNER,
            description="Generate an immutable research plan. Never search the web.",
            output_fields=frozenset({"plan", "plan_hash", "clarified_query"}),
        ),
        KB_RESEARCHER: AgentSpec(
            name=KB_RESEARCHER,
            description="Retrieve workspace-scoped knowledge chunks.",
            output_fields=frozenset({"evidence", "completed_tasks", "pending_tasks"}),
        ),
        WEB_RESEARCHER: AgentSpec(
            name=WEB_RESEARCHER,
            description="Search and fetch public sources.",
            output_fields=frozenset({"evidence", "completed_tasks", "pending_tasks"}),
        ),
        CRITIC: AgentSpec(
            name=CRITIC,
            description="Judge coverage and request at most one supplement round.",
            output_fields=frozenset({"critique", "critic_round"}),
        ),
        ANALYST: AgentSpec(
            name=ANALYST,
            description="Run the fixed sandbox script on verified evidence.",
            output_fields=frozenset({"analysis_artifacts"}),
        ),
        WRITER: AgentSpec(
            name=WRITER,
            description="Write the report using verified evidence only.",
            output_fields=frozenset({"report", "citations"}),
        ),
        SUPERVISOR: AgentSpec(
            name=SUPERVISOR,
            description="Route work to specialists via handoff tools.",
            allowed_handoffs=frozenset(),
        ),
        VERIFIER: AgentSpec(name=VERIFIER, description="Rule-based evidence gate, not an LLM agent."),
        SUPPLEMENT: AgentSpec(name=SUPPLEMENT, description="Install critic supplement tasks."),
        FINALIZE: AgentSpec(name=FINALIZE, description="Emit TASK_COMPLETED."),
    }
