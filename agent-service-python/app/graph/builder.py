"""组装 Supervisor + 专家图。

HITL 审批、规则核验、finalize 是系统闸门，不是 LLM Agent：
安全与协议终态不能交给模型自由发挥。
"""

from __future__ import annotations

import atexit
from typing import Any
from urllib.parse import quote

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt

from app.agents.critic import critic_review, route_after_critic, supplement_research
from app.agents.data_analysis import data_analysis
from app.agents.evidence_verifier import merge_evidence
from app.agents.knowledge_researcher import knowledge_research
from app.agents.planner import create_plan
from app.agents.researcher import web_research
from app.agents.runtime.registry import wrap_critic, wrap_research, wrap_verifier
from app.agents.runtime.supervisor import run_supervisor
from app.agents.writer import finalize, write_report
from app.core.config import get_settings
from app.graph.state import ResearchState

# Checkpoint 单例，thread_id=taskId:runId
_checkpointer: BaseCheckpointSaver[Any] | None = None
_checkpoint_pool: Any | None = None
_compiled = None


def wait_for_approval(state: ResearchState) -> dict[str, Any]:
    """PLAN 阶段暂停；Java 使用 Command(resume=...) 从同一节点恢复。"""

    if not state.get("require_plan_approval"):
        return {"phase": "EXECUTE", "approved": True, "status": "RUNNING"}
    if state.get("phase") == "EXECUTE" and state.get("approved"):
        return {"status": "RUNNING"}
    decision = interrupt({
        "type": "APPROVAL_REQUIRED",
        "planRevision": state.get("plan_revision") or 1,
        "planHash": state.get("plan_hash"),
    })
    if (not isinstance(decision, dict) or decision.get("approved") is not True
            or decision.get("approvedPlanHash") != state.get("plan_hash")):
        raise ValueError("plan approval payload is invalid")
    return {"phase": "EXECUTE", "approved": True, "status": "RUNNING"}


def _checkpoint_uri() -> str:
    """构造 psycopg 可识别的 PostgreSQL URI。"""
    settings = get_settings()
    user = quote(settings.postgres_user, safe="")
    password = quote(settings.postgres_password, safe="")
    database = quote(settings.postgres_db, safe="")
    options = quote(
        f"-c statement_timeout={max(1000, settings.postgres_statement_timeout_ms)}",
        safe="",
    )
    return (
        f"postgresql://{user}:{password}@{settings.postgres_host}:"
        f"{settings.postgres_port}/{database}"
        f"?connect_timeout={max(1, settings.postgres_connect_timeout_seconds)}"
        f"&options={options}"
    )


def _close_checkpointer() -> None:
    """进程退出时关闭 PostgreSQL Checkpoint 连接。"""
    global _checkpoint_pool
    if _checkpoint_pool is not None:
        _checkpoint_pool.close()
        _checkpoint_pool = None


def get_checkpointer() -> BaseCheckpointSaver[Any]:
    """返回配置的 Checkpoint 后端，生产配置失败时直接阻止任务启动。"""
    global _checkpointer, _checkpoint_pool
    if _checkpointer is not None:
        return _checkpointer

    backend = get_settings().checkpoint_backend.strip().lower()
    if backend == "memory":
        _checkpointer = MemorySaver()
        return _checkpointer
    if backend != "postgres":
        raise RuntimeError(f"unsupported CHECKPOINT_BACKEND: {backend}")

    try:
        from langgraph.checkpoint.postgres import PostgresSaver
        from psycopg.rows import dict_row
        from psycopg_pool import ConnectionPool
    except ImportError as exc:
        raise RuntimeError(
            "PostgreSQL checkpoint backend requires langgraph-checkpoint-postgres"
        ) from exc

    settings = get_settings()
    pool = ConnectionPool(
        _checkpoint_uri(),
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
        min_size=1,
        max_size=max(1, settings.checkpoint_pool_max_size),
        timeout=max(1, settings.postgres_connect_timeout_seconds),
        open=True,
    )
    try:
        pool.wait(timeout=max(1, settings.postgres_connect_timeout_seconds))
        checkpointer = PostgresSaver(pool)
        checkpointer.setup()
    except Exception:
        pool.close()
        raise
    _checkpoint_pool = pool
    _checkpointer = checkpointer
    return checkpointer


atexit.register(_close_checkpointer)


def _route_after_node(state: ResearchState) -> str:
    """任一节点失败后立即终止，禁止后续节点覆盖终态。"""
    return "stop" if state.get("status") == "FAILED" else "continue"


def _route_after_planner(state: ResearchState) -> str:
    """Planner 后：失败结束；需审批则 HITL；否则回 Supervisor。"""
    if state.get("status") == "FAILED":
        return "stop"
    if state.get("require_plan_approval") and not state.get("approved"):
        return "approve"
    return "supervise"


def _route_after_critic(state: ResearchState) -> str:
    route = route_after_critic(state)
    return "analysis" if route == "write" and state.get("enable_data_analysis") else route


def build_graph(checkpointer: BaseCheckpointSaver[Any] | None = None):
    """
    编译 Supervisor 主循环图。

    Supervisor 用 Command(goto=...) 选择专家；审批 / 核验 / finalize 走固定边。
    """
    graph = StateGraph(ResearchState)
    graph.add_node("supervisor", run_supervisor)
    graph.add_node("create_plan", create_plan)
    graph.add_node("wait_for_approval", wait_for_approval)
    graph.add_node("knowledge_research", wrap_research(knowledge_research))
    graph.add_node("web_research", wrap_research(web_research))
    graph.add_node("merge_evidence", wrap_verifier(merge_evidence))
    graph.add_node("critic_review", wrap_critic(critic_review))
    graph.add_node("supplement_research", supplement_research)
    graph.add_node("data_analysis", data_analysis)
    graph.add_node("write_report", write_report)
    graph.add_node("finalize", finalize)

    graph.add_edge(START, "supervisor")
    graph.add_conditional_edges(
        "create_plan",
        _route_after_planner,
        {"stop": END, "approve": "wait_for_approval", "supervise": "supervisor"},
    )
    graph.add_conditional_edges(
        "wait_for_approval", _route_after_node, {"stop": END, "continue": "supervisor"})
    graph.add_conditional_edges(
        "knowledge_research", _route_after_node, {"stop": END, "continue": "supervisor"})
    graph.add_conditional_edges(
        "web_research", _route_after_node, {"stop": END, "continue": "supervisor"})
    # 核验是进 Critic 的硬闸门：Supervisor 不能直接跳过。
    graph.add_conditional_edges(
        "merge_evidence", _route_after_node, {"stop": END, "continue": "critic_review"})
    graph.add_conditional_edges(
        "critic_review",
        _route_after_critic,
        {"stop": END, "supplement": "supplement_research", "write": "write_report", "analysis": "data_analysis"},
    )
    graph.add_conditional_edges(
        "supplement_research",
        _route_after_node,
        {"stop": END, "continue": "supervisor"},
    )
    graph.add_conditional_edges("data_analysis", _route_after_node, {"stop": END, "continue": "write_report"})
    graph.add_conditional_edges(
        "write_report", _route_after_node, {"stop": END, "continue": "finalize"})
    graph.add_edge("finalize", END)

    return graph.compile(checkpointer=checkpointer or get_checkpointer())


def get_compiled_graph():
    """返回进程内单例编译图，状态由配置的 Checkpoint 后端持久化。"""
    global _compiled
    if _compiled is None:
        _compiled = build_graph()
    return _compiled


def delete_thread_checkpoint(task_id: str) -> None:
    """完整重试前清除旧执行轮次的图状态。"""
    get_checkpointer().delete_thread(task_id)


def reset_graph_for_tests() -> None:
    """测试辅助：释放全局图和 Checkpoint。"""
    global _compiled, _checkpointer
    _compiled = None
    _close_checkpointer()
    _checkpointer = None
