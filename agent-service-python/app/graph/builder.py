"""构建第 1 周最小 LangGraph：Planner → Supervisor → Researcher → write_report → finalize。"""

from __future__ import annotations

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph

from app.agents.planner import create_plan
from app.agents.researcher import web_research
from app.agents.supervisor import dispatch_tasks
from app.agents.writer import finalize, write_report
from app.graph.state import ResearchState

# 进程内 Checkpoint，thread_id=taskId
_memory = MemorySaver()
_compiled = None


def build_graph(checkpointer: MemorySaver | None = None):
    """
    编译研究图。

    Args:
        checkpointer: 可选自定义 checkpointer；默认 MemorySaver。
    """
    graph = StateGraph(ResearchState)
    graph.add_node("create_plan", create_plan)
    graph.add_node("dispatch_tasks", dispatch_tasks)
    graph.add_node("web_research", web_research)
    graph.add_node("write_report", write_report)
    graph.add_node("finalize", finalize)

    graph.add_edge(START, "create_plan")
    graph.add_edge("create_plan", "dispatch_tasks")
    graph.add_edge("dispatch_tasks", "web_research")
    graph.add_edge("web_research", "write_report")
    graph.add_edge("write_report", "finalize")
    graph.add_edge("finalize", END)

    return graph.compile(checkpointer=checkpointer or _memory)


def get_compiled_graph():
    """返回进程内单例编译图。"""
    global _compiled
    if _compiled is None:
        _compiled = build_graph()
    return _compiled
