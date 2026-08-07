"""Researcher Agent：检索并产出带来源的证据对象。"""

from __future__ import annotations

import json
import re
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.events import make_event
from app.graph.deadline import remaining_seconds
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.tools.web_fetch import fetch_url
from app.tools.web_search import search_web

_SYNTHETIC_SYSTEM = """你是研究助理。在没有实时搜索结果时，请基于公开常识生成结构化研究笔记。
必须输出 JSON 数组，不要 Markdown。每项：
{"title":"...","url":"https://example.com/...","snippet":"关键摘录","sourceType":"SYNTHETIC"}
要求：2～4 条；url 使用合理的官方/文档风格占位；snippet 不少于 40 字；明确这是演示用合成证据。
"""


def _extract_json_array(text: str) -> list[dict[str, Any]]:
    """从模型输出提取 JSON 数组。"""
    text = text.strip()
    try:
        data = json.loads(text)
        if isinstance(data, list):
            return data
        if isinstance(data, dict) and "items" in data:
            return list(data["items"])
    except json.JSONDecodeError:
        pass
    match = re.search(r"\[[\s\S]*\]", text)
    if not match:
        return []
    return list(json.loads(match.group(0)))


def _mock_evidence(query: str) -> list[dict[str, Any]]:
    """确定性合成证据（单测 / 无 Key）。"""
    return [
        {
            "title": f"Overview related to {query[:48]}",
            "url": "https://docs.example.com/overview",
            "snippet": f"关于「{query}」的公开资料摘要：能力边界、生态与部署方式是企业选型的核心维度。",
            "sourceType": "SYNTHETIC",
        },
        {
            "title": "Multi-agent orchestration patterns",
            "url": "https://docs.example.com/multi-agent",
            "snippet": "Supervisor 模式适合将规划、检索与写作拆分，降低单 Agent 上下文污染，并便于做权限隔离。",
            "sourceType": "SYNTHETIC",
        },
    ]


def _to_evidence(items: list[dict[str, Any]], task_ref: str) -> list[dict[str, Any]]:
    """规范化证据对象。"""
    evidence: list[dict[str, Any]] = []
    for idx, item in enumerate(items, start=1):
        evidence.append(
            {
                "id": f"ev-{task_ref}-{idx}",
                "sourceTitle": item.get("title") or item.get("sourceTitle") or "Untitled",
                "sourceUri": item.get("url") or item.get("sourceUri") or "",
                "quotedText": item.get("snippet") or item.get("quotedText") or "",
                "sourceType": item.get("sourceType") or "WEB",
                "documentId": item.get("documentId"),
                "chunkId": item.get("chunkId"),
                "verified": False,
            }
        )
    return evidence


def web_research(state: ResearchState) -> dict[str, Any]:
    """
    Researcher 节点：对 pending web_research 任务收集证据。

    Returns:
        evidence / completed_tasks / events 等状态增量。
    """
    settings = get_settings()
    step, limit_failure = claim_step(state, "web_research")
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
            node="web_research",
            data={"agent": "Researcher"},
        )
    )

    pending = list(state.get("pending_tasks") or [])
    all_evidence = list(state.get("evidence") or [])
    completed = list(state.get("completed_tasks") or [])

    for sub in pending:
        description = sub.get("description") or state.get("clarified_query") or state["user_query"]
        query = str(description)

        delta.append(
            make_event(
                events=events + delta,
                task_id=task_id,
                run_id=run_id,
                event_type="TOOL_CALLED",
                node="web_research",
                data={"tool": "web_search", "query": query},
            )
        )

        raw: list[dict[str, Any]] = []
        if state.get("enable_web_search", True):
            try:
                raw = search_web(query, timeout_seconds=remaining_seconds(state, 30))
            except Exception as exc:  # noqa: BLE001 - 工具失败降级
                delta.append(
                    make_event(
                        events=events + delta,
                        task_id=task_id,
                        run_id=run_id,
                        event_type="TOOL_COMPLETED",
                        node="web_research",
                        data={"tool": "web_search", "ok": False, "error": str(exc)},
                    )
                )
                raw = []

        if raw:
            delta.append(
                make_event(
                    events=events + delta,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="TOOL_COMPLETED",
                    node="web_research",
                    data={"tool": "web_search", "ok": True, "count": len(raw)},
                )
            )
            # 对首条结果尝试网页抽取，丰富 snippet（失败忽略）
            first_url = raw[0].get("url")
            if first_url:
                fetched = fetch_url(str(first_url), timeout_seconds=remaining_seconds(state, 12))
                if fetched:
                    raw[0] = {**raw[0], **fetched}
            evidence = _to_evidence(raw, str(sub.get("id") or "x"))
        else:
            # SYNTHETIC 降级：保证验收可演示且仍带来源字段
            if settings.agent_mock_llm or not settings.deepseek_api_key:
                synthetic_items = _mock_evidence(query)
            else:
                model = get_chat_model(
                    temperature=0.2,
                    timeout_seconds=remaining_seconds(state, 60),
                )
                resp = model.invoke(
                    [
                        SystemMessage(content=_SYNTHETIC_SYSTEM),
                        HumanMessage(content=query),
                    ]
                )
                synthetic_items = _extract_json_array(str(resp.content)) or _mock_evidence(query)
            evidence = _to_evidence(synthetic_items, str(sub.get("id") or "x"))
            delta.append(
                make_event(
                    events=events + delta,
                    task_id=task_id,
                    run_id=run_id,
                    event_type="TOOL_COMPLETED",
                    node="web_research",
                    data={"tool": "synthetic_notes", "ok": True, "count": len(evidence)},
                )
            )

        all_evidence.extend(evidence)
        completed.append({**sub, "status": "DONE", "evidenceCount": len(evidence)})

    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_COMPLETED",
            node="web_research",
            data={"agent": "Researcher", "sourceCount": len(all_evidence)},
        )
    )

    return {
        "evidence": all_evidence,
        "pending_tasks": [],
        "completed_tasks": completed,
        "step_count": step,
        "events": delta,
    }
