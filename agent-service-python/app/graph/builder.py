"""构建研究图：Planner → Supervisor → Knowledge → Web → write_report → finalize。"""

from __future__ import annotations

import atexit
from typing import Any
from urllib.parse import quote

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph

from app.agents.knowledge_researcher import knowledge_research
from app.agents.planner import create_plan
from app.agents.researcher import web_research
from app.agents.supervisor import dispatch_tasks
from app.agents.writer import finalize, write_report
from app.core.config import get_settings
from app.graph.state import ResearchState

from langgraph.types import interrupt


# Checkpoint 单例，thread_id=taskId
_checkpointer: BaseCheckpointSaver[Any] | None = None
_checkpoint_pool: Any | None = None
_compiled = None


def wait_for_approval(state: ResearchState) -> dict[str, Any]:
    """PLAN 阶段暂停；第二天使用 Command(resume=...) 从同一节点恢复。"""

    if not state.get("require_plan_approval"):
        return {"phase": "EXECUTE", "approved": True, "status": "RUNNING"}
    if state.get("phase") == "EXECUTE" and state.get("approved"):
        return {"status": "RUNNING"}
    decision = interrupt({
        "type": "APPROVAL_REQUIRED",
        "planRevision": state.get("plan_revision") or 1,
        "planHash": state.get("plan_hash"),
    })
    if not isinstance(decision, dict) or decision.get("approved") is not True:
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


def build_graph(checkpointer: BaseCheckpointSaver[Any] | None = None):
    """
    编译研究图。

    Args:
        checkpointer: 可选自定义 checkpointer；默认使用配置的持久化后端。
    """
    graph = StateGraph(ResearchState)
    # 添加节点
    graph.add_node("create_plan", create_plan)
    graph.add_node("wait_for_approval", wait_for_approval)
    graph.add_node("dispatch_tasks", dispatch_tasks)
    graph.add_node("knowledge_research", knowledge_research)
    graph.add_node("web_research", web_research)
    graph.add_node("write_report", write_report)
    graph.add_node("finalize", finalize)

    # 添加边
    graph.add_edge(START, "create_plan")
    graph.add_conditional_edges(
        "create_plan", _route_after_node, {"stop": END, "continue": "wait_for_approval"})
    graph.add_conditional_edges(
        "wait_for_approval", _route_after_node, {"stop": END, "continue": "dispatch_tasks"})
    graph.add_conditional_edges(
        "dispatch_tasks", _route_after_node, {"stop": END, "continue": "knowledge_research"})
    graph.add_conditional_edges(
        "knowledge_research", _route_after_node, {"stop": END, "continue": "web_research"})
    graph.add_conditional_edges(
        "web_research", _route_after_node, {"stop": END, "continue": "write_report"})
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
