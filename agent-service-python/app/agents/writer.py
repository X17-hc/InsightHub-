"""Writer：结论只用 verified 证据。未验证来源只能出现在限制节，不能当事实。"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.config import get_stream_writer

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.deadline import remaining_seconds
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState

_WRITER_SYSTEM = """你是技术研究报告撰写助手。
只根据 verified=true 的证据写结论，不要编造不存在的来源编号。
未验证证据只能出现在「限制 / 未验证来源」中，不得作为结论依据。
若提供了 critique.limitations，必须写入限制章节。
结构必须包含：标题、摘要、研究发现、对比/要点、建议、限制、参考来源。
参考来源使用 [n] 形式，并与已验证证据列表一一对应。
"""


def _emit_custom_event(event: dict[str, Any]) -> None:
    """在 LangGraph stream_mode=custom 时实时推送事件；非流式 invoke 下静默跳过。"""
    try:
        get_stream_writer()(event)
    except Exception:  # noqa: BLE001 - 同步 invoke / 单测伪图可能没有 custom writer
        return


def _split_evidence(evidence: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """拆分已验证 / 未验证证据。"""
    verified = [ev for ev in evidence if ev.get("verified")]
    unverified = [ev for ev in evidence if not ev.get("verified")]
    return verified, unverified


def _critique_limitations(state: ResearchState) -> list[str]:
    """从 critique 提取限制说明。"""
    critique = state.get("critique") or {}
    raw = critique.get("limitations") or []
    return [str(item) for item in raw if item]


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
    """无 LLM 时的模板报告：结论仅引用 verified 证据。"""
    plan = state.get("plan") or {}
    title = plan.get("title") or "研究报告"
    query = state.get("clarified_query") or state.get("user_query") or ""
    all_evidence = list(state.get("evidence") or [])
    verified, unverified = _split_evidence(all_evidence)
    limitations = _critique_limitations(state)
    critique = state.get("critique") or {}
    verdict = critique.get("verdict")

    lines = [
        f"# {title}",
        "",
        "## 摘要",
        f"本报告围绕「{query}」整理多 Agent 协作研究得到的要点与来源。",
    ]
    if verdict == "FAIL":
        lines.append("评审未完全通过，下列结论带有明确限制。")
    lines.extend(["", "## 研究发现"])
    if verified:
        for i, ev in enumerate(verified, start=1):
            lines.append(f"{i}. {ev.get('quotedText', '')} [{i}]")
    else:
        lines.append("- （无已验证证据，不输出结论性发现）")

    lines.extend(["", "## 建议", "- 结合自身技术栈与可观测性要求进一步验证。", "", "## 限制"])
    if limitations:
        for item in limitations:
            lines.append(f"- {item}")
    else:
        lines.append("- 自动研究可能遗漏最新变更，关键决策请复核原始来源。")
    if unverified:
        lines.append("- 以下来源未通过核验，不得作为结论依据：")
        for ev in unverified:
            lines.append(
                f"  - {ev.get('sourceTitle')} - {ev.get('sourceUri')} ({ev.get('sourceType')})"
            )

    lines.extend(["", "## 参考来源"])
    if verified:
        for i, ev in enumerate(verified, start=1):
            lines.append(
                f"[{i}] {ev.get('sourceTitle')} - {ev.get('sourceUri')} ({ev.get('sourceType')})"
            )
    else:
        lines.append("- （无已验证参考来源）")
    return "\n".join(lines)


def _build_citations(evidence: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    构建引用列表：已验证证据优先编号；未验证保留但 verified=false。

    Writer 结论编号与已验证列表对齐；未验证追加在后供审计。
    """
    verified, unverified = _split_evidence(evidence)
    citations: list[dict[str, Any]] = []
    unique: list[dict[str, Any]] = []
    seen: set[str] = set()
    for ev in verified + unverified:
        key = str(ev.get("canonicalUri") or ev.get("sourceUri") or ev.get("id") or "").strip().lower()
        if key and key in seen:
            continue
        if key:
            seen.add(key)
        unique.append(ev)
    for i, ev in enumerate(unique, start=1):
        citations.append(
            {
                "citationNo": i,
                "sourceTitle": ev.get("sourceTitle"),
                "sourceUri": ev.get("sourceUri"),
                "canonicalUri": ev.get("canonicalUri"),
                "finalUri": ev.get("finalUri"),
                "sourceType": ev.get("sourceType") or "WEB",
                "documentId": ev.get("documentId"),
                "chunkId": ev.get("chunkId"),
                "quotedText": ev.get("quotedText"),
                "verified": bool(ev.get("verified")),
                "verificationStatus": ev.get("verificationStatus") or ("VERIFIED" if ev.get("verified") else "CANDIDATE"),
                "verificationReason": ev.get("verificationReason"),
                "retrievedAt": ev.get("retrievedAt"),
                "contentHash": ev.get("contentHash"),
                "httpStatus": ev.get("httpStatus"),
            }
        )
    return citations


def _append_guard_sections(report: str, state: ResearchState) -> str:
    """确保限制 / 未验证来源节存在（LLM 漏写时补齐）。"""
    limitations = _critique_limitations(state)
    _, unverified = _split_evidence(list(state.get("evidence") or []))
    out = report.rstrip()
    if limitations and "## 限制" not in out:
        out += "\n\n## 限制\n" + "\n".join(f"- {item}" for item in limitations)
    if unverified and "未通过核验" not in out and "未验证来源" not in out:
        if "## 限制" not in out:
            out += "\n\n## 限制"
        out += "\n- 以下来源未通过核验，不得作为结论依据："
        for ev in unverified:
            out += (
                f"\n  - {ev.get('sourceTitle')} - {ev.get('sourceUri')} "
                f"({ev.get('sourceType')})"
            )
    return out


def _sanitize_llm_report(report: str, state: ResearchState) -> str:
    """
    后置校验 LLM 报告：无 VERIFIED 或结论区泄漏候选摘录时回退模板。

    这是模型输出之后的最后一道证据边界：候选来源可以出现在“限制”部分，但不能
    支撑研究发现。回退模板仍保留质量结论和限制，不会把质量 FAIL 改成执行异常。

    Returns:
        合规 Markdown 报告。
    """
    verified, unverified = _split_evidence(list(state.get("evidence") or []))
    if not report or not report.lstrip().startswith("#"):
        return _render_fallback(state)
    if not verified:
        return _render_fallback(state)

    # 仅检查「限制」之前的正文，避免限制节中的合法列举误判
    body = report.split("## 限制")[0] if "## 限制" in report else report
    for ev in unverified:
        quote = (ev.get("quotedText") or "").strip()
        if len(quote) >= 24 and quote in body:
            return _render_fallback(state)
    return _append_guard_sections(report, state)


def write_report(state: ResearchState) -> dict[str, Any]:
    """仅以 VERIFIED 证据生成 Markdown 报告并建立版本内引用。

    流式 REPORT_DELTA 按 index 递增；最后一个 delta 标记 done。流式调用失败时
    允许在剩余 deadline 内退回一次非流式调用，但不能回退到 synthetic 报告。
    引用编号由规范化后的证据顺序确定，候选来源只进入限制说明。
    """
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

    all_evidence = list(state.get("evidence") or [])
    verified, unverified = _split_evidence(all_evidence)
    limitations = _critique_limitations(state)

    if settings.synthetic_allowed():
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
        if not settings.deepseek_api_key.strip():
            raise RuntimeError("LLM_NOT_CONFIGURED")
        model = get_chat_model(temperature=0.3, timeout_seconds=remaining_seconds(state, 60))
        # 结论上下文只传 verified；未验证仅作限制提示，降低模型误用概率
        payload = {
            "query": state.get("clarified_query") or state.get("user_query"),
            "plan": state.get("plan"),
            "critique": {
                "verdict": (state.get("critique") or {}).get("verdict"),
                "limitations": limitations,
            },
            "verifiedEvidence": verified,
            "unverifiedSourceTitles": [
                ev.get("sourceTitle") for ev in unverified if ev.get("sourceTitle")
            ],
            "limitations": limitations,
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
                    text = "".join(
                        str(item.get("text", item)) if isinstance(item, dict) else str(item)
                        for item in content
                    )
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
        report = _sanitize_llm_report(report, state)
        delta.extend(
            _report_delta_events(
                report=report,
                events=events + delta,
                task_id=task_id,
                run_id=run_id,
            )
        )

    citations = _build_citations(all_evidence)
    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_COMPLETED",
            node="write_report",
            data={
                "chars": len(report),
                "citationCount": len(citations),
                "verifiedCitationCount": len(verified),
            },
        )
    )
    _emit_custom_event(delta[-1])

    return {
        "report": report,
        "citations": citations,
        "step_count": step,
        "events": delta,
    }


def finalize(state: ResearchState) -> dict[str, Any]:
    """形成规范任务终态和不可变质量快照。

    ``TASK_COMPLETED`` 是图内过程事件；Java 真正持久化报告、引用和任务投影时
    依赖随后构造的 ``TASK_RESULT`` 协议终态。Critic FAIL 仍返回 COMPLETED，
    而执行异常不得进入本节点。
    """
    if state.get("status") == "FAILED":
        return {}
    events = list(state.get("events") or [])
    critique = state.get("critique") or {}
    citations = list(state.get("citations") or [])
    verified_count = sum(1 for item in citations if item.get("verificationStatus") == "VERIFIED" or item.get("verified"))
    candidate_count = sum(1 for item in citations if item.get("verificationStatus") == "CANDIDATE")
    quality = {
        "verdict": critique.get("verdict") or "NOT_EVALUATED",
        "summary": critique.get("summary") or "",
        "gaps": list(critique.get("gaps") or []),
        "limitations": list(critique.get("limitations") or []),
        "criticRound": int(state.get("critic_round") or 0),
        "maxCriticRounds": int(state.get("max_critic_rounds") or 0),
        "verifiedCitationCount": verified_count,
        "candidateCitationCount": candidate_count,
        "totalCitationCount": len(citations),
    }
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
                "quality": quality,
            },
        )
    ]
    return {"status": "COMPLETED", "quality": quality, "events": delta}
