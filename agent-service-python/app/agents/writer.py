"""报告生成节点：仅使用证据组装 Markdown（非独立 Agent 角色）。"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.events import make_event
from app.graph.state import ResearchState

_WRITER_SYSTEM = """你是技术研究报告撰写助手。
只根据提供的证据写 Markdown 报告，不要编造不存在的来源编号。
结构必须包含：标题、摘要、研究发现、对比/要点、建议、限制、参考来源。
参考来源使用 [n] 形式，并与证据列表一一对应。
"""


def _render_fallback(state: ResearchState) -> str:
    """无 LLM 时的模板报告。"""
    plan = state.get("plan") or {}
    title = plan.get("title") or "研究报告"
    query = state.get("clarified_query") or state.get("user_query") or ""
    evidence = state.get("evidence") or []
    lines = [
        f"# {title}",
        "",
        "## 摘要",
        f"本报告围绕「{query}」整理多 Agent 协作研究得到的要点与来源。",
        "",
        "## 研究发现",
    ]
    for i, ev in enumerate(evidence, start=1):
        lines.append(f"{i}. {ev.get('quotedText', '')} [{i}]")
    lines.extend(["", "## 建议", "- 结合自身技术栈与可观测性要求进一步验证。", "", "## 限制", "- 第 1 周演示可能包含 SYNTHETIC 证据，上线前需替换为真实检索。", "", "## 参考来源"])
    for i, ev in enumerate(evidence, start=1):
        lines.append(f"[{i}] {ev.get('sourceTitle')} - {ev.get('sourceUri')} ({ev.get('sourceType')})")
    return "\n".join(lines)


def write_report(state: ResearchState) -> dict[str, Any]:
    """基于 evidence 生成 Markdown 报告。"""
    settings = get_settings()
    events = list(state.get("events") or [])
    step = int(state.get("step_count") or 0) + 1
    task_id = state["task_id"]
    run_id = state["run_id"]
    delta: list[dict[str, Any]] = []

    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_STARTED",
            node="write_report",
            data={},
        )
    )

    evidence = state.get("evidence") or []
    if settings.agent_mock_llm or not settings.deepseek_api_key:
        report = _render_fallback(state)
    else:
        model = get_chat_model(temperature=0.3)
        payload = {
            "query": state.get("clarified_query") or state.get("user_query"),
            "plan": state.get("plan"),
            "evidence": evidence,
        }
        resp = model.invoke(
            [
                SystemMessage(content=_WRITER_SYSTEM),
                HumanMessage(content=str(payload)),
            ]
        )
        report = str(resp.content).strip()
        if not report.startswith("#"):
            report = _render_fallback(state)

    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_COMPLETED",
            node="write_report",
            data={"chars": len(report), "citationCount": len(evidence)},
        )
    )

    return {"report": report, "step_count": step, "events": delta}


def finalize(state: ResearchState) -> dict[str, Any]:
    """收尾节点：标记任务完成。"""
    events = list(state.get("events") or [])
    delta = [
        make_event(
            events=events,
            task_id=state["task_id"],
            run_id=state["run_id"],
            event_type="TASK_COMPLETED",
            node="finalize",
            data={"hasReport": bool(state.get("report"))},
        )
    ]
    return {"status": "COMPLETED", "events": delta}
