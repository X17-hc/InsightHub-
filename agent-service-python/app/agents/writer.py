"""报告生成节点：仅使用证据组装 Markdown（非独立 Agent 角色）。"""

from __future__ import annotations

from typing import Any

from langgraph.config import get_stream_writer
from langchain_core.messages import HumanMessage, SystemMessage

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.events import make_event
from app.graph.deadline import remaining_seconds
from app.graph.limits import claim_step
from app.graph.state import ResearchState

_WRITER_SYSTEM = """你是技术研究报告撰写助手。
只根据提供的证据写 Markdown 报告，不要编造不存在的来源编号。
结构必须包含：标题、摘要、研究发现、对比/要点、建议、限制、参考来源。
参考来源使用 [n] 形式，并与证据列表一一对应。
"""


def _emit_custom_event(event: dict[str, Any]) -> None:
    """在 LangGraph stream_mode=custom 时实时推送事件；非流式 invoke 下静默跳过。"""
    try:
        get_stream_writer()(event)
    except Exception:  # noqa: BLE001 - 同步 invoke / 单测伪图可能没有 custom writer
        return


def _report_delta_events(
    *,
    report: str,
    events: list[dict[str, Any]],
    task_id: str,
    run_id: str,
    node: str = "write_report",
    chunk_size: int = 1200,
) -> list[dict[str, Any]]:
    """将已有报告切成实时事件，用于 mock/fallback 或不支持 token stream 的路径。"""
    if not report:
        return []
    chunks = [report[i : i + chunk_size] for i in range(0, len(report), chunk_size)]
    cursor = list(events)
    out: list[dict[str, Any]] = []
    for index, chunk in enumerate(chunks, start=1):
        event = make_event(
            events=cursor,
            task_id=task_id,
            run_id=run_id,
            event_type="REPORT_DELTA",
            node=node,
            data={
                "delta": chunk,
                "index": index,
                "total": len(chunks),
                "done": index == len(chunks),
            },
        )
        out.append(event)
        cursor.append(event)
        _emit_custom_event(event)
    return out


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
    step, limit_failure = claim_step(state, "write_report")
    if limit_failure is not None:
        return limit_failure
    events = list(state.get("events") or [])
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
    _emit_custom_event(delta[-1])

    evidence = state.get("evidence") or []
    if settings.agent_mock_llm or not settings.deepseek_api_key:
        report = _render_fallback(state)
        delta.extend(
            _report_delta_events(
                report=report,
                events=events + delta,
                task_id=task_id,
                run_id=run_id,
            )
        )
    else:
        model = get_chat_model(temperature=0.3, timeout_seconds=remaining_seconds(state, 60))
        payload = {
            "query": state.get("clarified_query") or state.get("user_query"),
            "plan": state.get("plan"),
            "evidence": evidence,
        }
        messages = [
            SystemMessage(content=_WRITER_SYSTEM),
            HumanMessage(content=str(payload)),
        ]
        chunks: list[str] = []
        pending = ""
        chunk_index = 0
        stream_failed = False
        try:
            for piece in model.stream(messages):
                content = getattr(piece, "content", "")
                if isinstance(content, list):
                    text = "".join(str(item.get("text", item)) if isinstance(item, dict) else str(item) for item in content)
                else:
                    text = str(content or "")
                if not text:
                    continue
                chunks.append(text)
                pending += text
                if len(pending) < 1200:
                    continue
                chunk_index += 1
                event = make_event(
                    events=events + delta,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="REPORT_DELTA",
                    node="write_report",
                    data={"delta": pending, "index": chunk_index, "done": False},
                )
                delta.append(event)
                _emit_custom_event(event)
                pending = ""
        except Exception:  # noqa: BLE001 - 降级到非流式，保留原有稳定性
            stream_failed = True

        if stream_failed or not chunks:
            resp = model.invoke(messages)
            report = str(resp.content).strip()
            delta.extend(
                _report_delta_events(
                    report=report,
                    events=events + delta,
                    task_id=task_id,
                    run_id=run_id,
                )
            )
        else:
            if pending:
                chunk_index += 1
                event = make_event(
                    events=events + delta,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="REPORT_DELTA",
                    node="write_report",
                    data={"delta": pending, "index": chunk_index, "done": True},
                )
                delta.append(event)
                _emit_custom_event(event)
            report = "".join(chunks).strip()
        if not report.startswith("#"):
            report = _render_fallback(state)
            delta.extend(
                _report_delta_events(
                    report=report,
                    events=events + delta,
                    task_id=task_id,
                    run_id=run_id,
                )
            )

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
    _emit_custom_event(delta[-1])

    # 结构化引用，供 Java 落库 citation 表
    citations: list[dict[str, Any]] = []
    for i, ev in enumerate(evidence, start=1):
        citations.append(
            {
                "citationNo": i,
                "sourceTitle": ev.get("sourceTitle"),
                "sourceUri": ev.get("sourceUri"),
                "sourceType": ev.get("sourceType") or "WEB",
                "documentId": ev.get("documentId"),
                "chunkId": ev.get("chunkId"),
                "quotedText": ev.get("quotedText"),
                "verified": bool(ev.get("verified")),
            }
        )

    return {
        "report": report,
        "citations": citations,
        "step_count": step,
        "events": delta,
    }


def finalize(state: ResearchState) -> dict[str, Any]:
    """收尾节点：标记任务完成。"""
    if state.get("status") == "FAILED":
        return {}
    events = list(state.get("events") or [])
    delta = [
        make_event(
            events=events,
            task_id=state["task_id"],
            run_id=state["run_id"],
            event_type="TASK_COMPLETED",
            node="finalize",
            data={
                "hasReport": bool(state.get("report")),
                "citationCount": len(state.get("citations") or []),
            },
        )
    ]
    return {"status": "COMPLETED", "events": delta}
