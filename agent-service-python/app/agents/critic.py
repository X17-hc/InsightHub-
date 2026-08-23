"""Critic Agent：评审证据与计划覆盖；最多触发一轮补充研究。"""

from __future__ import annotations

import json
import re
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.deadline import remaining_seconds
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.schemas.protocol import CritiqueResult, SupplementTask

_CRITIC_SYSTEM = """你是 InsightHub Critic。只输出 JSON，不要 Markdown。
格式：{"verdict":"PASS|SUPPLEMENT|FAIL","summary":"...","gaps":["..."],
"limitations":["..."],"supplementTasks":[{"id":"sup-1","type":"web_research|knowledge_research","description":"..."}]}
规则：
1. 关键结论必须有 verified=true 的证据支撑，否则不得 PASS。
2. 计划任务未覆盖或证据冲突/过期/重复时优先 SUPPLEMENT（最多 2 个补充任务）。
3. 若已是最后一轮（cannotSupplement=true），只能 PASS 或 FAIL，并在 limitations 说明限制。
4. 禁止输出模型原始推理长文或密钥。
"""


def _extract_json(text: str) -> dict[str, Any]:
    """从模型输出提取 JSON 对象。"""
    try:
        return json.loads(text.strip())
    except json.JSONDecodeError:
        match = re.search(r"\{[\s\S]*\}", text)
        if not match:
            raise
        return json.loads(match.group(0))


def _evidence_summary(evidence: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """裁剪 Critic 输入：仅必要字段。"""
    out: list[dict[str, Any]] = []
    for ev in evidence:
        out.append(
            {
                "id": ev.get("id"),
                "sourceTitle": ev.get("sourceTitle"),
                "sourceType": ev.get("sourceType"),
                "verified": bool(ev.get("verified")),
                "quotedText": (ev.get("quotedText") or "")[:240],
            }
        )
    return out


def _default_supplement_tasks(*, has_kb: bool, reason: str) -> tuple[SupplementTask, ...]:
    """构造默认补充任务；无知识库时不下发 knowledge_research。"""
    tasks = [
        SupplementTask(
            id="sup-web-1",
            type="web_research",
            description=reason or "补充检索官方文档与权威对比资料以支撑关键结论",
        )
    ]
    if has_kb:
        tasks.append(
            SupplementTask(
                id="sup-kb-1",
                type="knowledge_research",
                description="从内部知识库补充与计划目标相关的已验证片段",
            )
        )
    return tuple(tasks[:2])


def _mock_critique(
    *,
    evidence: list[dict[str, Any]],
    completed_tasks: list[dict[str, Any]],
    plan: dict[str, Any] | None,
    can_supplement: bool,
    has_kb: bool,
) -> CritiqueResult:
    """无 LLM 时的确定性评审：支撑单测与演示。"""
    verified = [e for e in evidence if e.get("verified")]
    # 无证据或无已验证证据：首轮优先 SUPPLEMENT，用尽轮次再 FAIL
    if (not evidence or not verified) and can_supplement:
        gap = "missing_evidence" if not evidence else "unverified_evidence"
        summary = (
            "未收集到任何来源，请求补充研究。"
            if not evidence
            else "首轮证据未通过核验，请求补充研究。"
        )
        return CritiqueResult(
            verdict="SUPPLEMENT",
            summary=summary,
            gaps=(gap,),
            limitations=(),
            supplement_tasks=_default_supplement_tasks(
                has_kb=has_kb,
                reason="补充检索官方文档与权威对比资料以支撑关键结论",
            ),
        )
    if not evidence:
        return CritiqueResult(
            verdict="FAIL",
            summary="无可用证据，无法形成可靠结论。",
            gaps=("missing_evidence",),
            limitations=("未收集到任何来源",),
            supplementTasks=(),
        )
    if not verified:
        return CritiqueResult(
            verdict="FAIL",
            summary="证据均未通过核验，且已用尽补充轮次。",
            gaps=("unverified_evidence",),
            limitations=("报告结论仅供参考，缺乏已验证来源",),
            supplementTasks=(),
        )

    plan_tasks = list((plan or {}).get("tasks") or [])
    if plan_tasks and not completed_tasks and can_supplement:
        return CritiqueResult.model_validate(
            {
                "verdict": "SUPPLEMENT",
                "summary": "计划任务完成情况不足，请求补充检索。",
                "gaps": ["incomplete_plan_coverage"],
                "limitations": [],
                "supplementTasks": [
                    {
                        "id": "sup-web-1",
                        "type": "web_research",
                        "description": "覆盖计划中尚未充分取证的研究问题",
                    }
                ],
            }
        )

    return CritiqueResult(
        verdict="PASS",
        summary="已验证证据足以支撑报告撰写。",
        gaps=(),
        limitations=(),
        supplementTasks=(),
    )


def _force_terminal_verdict(result: CritiqueResult) -> CritiqueResult:
    """第二轮禁止 SUPPLEMENT：降级为 FAIL 并写入限制说明。"""
    if result.verdict != "SUPPLEMENT":
        return result
    limitations = list(result.limitations) + [
        "已达到最大 Critic 轮次，补充研究请求被拒绝并以限制说明收尾"
    ]
    return CritiqueResult(
        verdict="FAIL",
        summary=result.summary or "补充请求超出轮次上限。",
        gaps=result.gaps,
        limitations=tuple(limitations),
        supplementTasks=(),
    )


def _enforce_verdict_invariants(
    result: CritiqueResult,
    *,
    evidence: list[dict[str, Any]],
    can_supplement: bool,
    has_kb: bool,
    plan: dict[str, Any] | None = None,
) -> CritiqueResult:
    """
    硬约束：无 verified 证据时禁止 PASS；可补充时改为 SUPPLEMENT，否则 FAIL。

    同时清洗无 KB 时的 knowledge_research 补充任务。
    """
    verified = [e for e in evidence if e.get("verified")]
    minimum_sources = max(3, int(((plan or {}).get("sourceRequirements") or {}).get("minVerifiedSources") or 3))
    tasks = list(result.supplement_tasks)
    if not has_kb:
        tasks = [t for t in tasks if t.type != "knowledge_research"]

    verdict = result.verdict
    gaps = list(result.gaps)
    limitations = list(result.limitations)
    summary = result.summary

    if verdict == "PASS" and len(verified) < minimum_sources:
        if can_supplement:
            verdict = "SUPPLEMENT"
            summary = summary or "已核验来源未达到计划要求，请求补充研究。"
            if "unverified_evidence" not in gaps and "missing_evidence" not in gaps:
                gaps.append("insufficient_verified_sources" if verified else ("unverified_evidence" if evidence else "missing_evidence"))
            if not tasks:
                tasks = list(
                    _default_supplement_tasks(
                        has_kb=has_kb,
                        reason="补充检索以获取可核验来源",
                    )
                )
        else:
            verdict = "FAIL"
            summary = summary or "已核验来源未达到计划要求，无法通过评审。"
            if "unverified_evidence" not in gaps and "missing_evidence" not in gaps:
                gaps.append("insufficient_verified_sources" if verified else ("unverified_evidence" if evidence else "missing_evidence"))
            limitations = list(
                dict.fromkeys(
                    limitations
                    + ["报告结论仅供参考，缺乏已验证来源"]
                )
            )
            tasks = []

    if verdict == "SUPPLEMENT" and not tasks:
        tasks = list(
            _default_supplement_tasks(
                has_kb=has_kb,
                reason="补充检索以覆盖 Critic 指出的证据缺口",
            )
        )

    return CritiqueResult(
        verdict=verdict,
        summary=summary,
        gaps=tuple(gaps),
        limitations=tuple(limitations),
        supplement_tasks=tuple(tasks[:2]),
    )


def critic_review(state: ResearchState) -> dict[str, Any]:
    """
    Critic 节点：产出 CritiqueResult，并递增 critic_round。

    Returns:
        critique / critic_round / events / step_count 增量。
    """
    settings = get_settings()
    step, limit_failure = claim_step(state, "critic_review")
    if limit_failure is not None:
        return limit_failure

    events = list(state.get("events") or [])
    task_id = state["task_id"]
    run_id = state["run_id"]
    prior_round = int(state.get("critic_round") or 0)
    max_rounds = int(state.get("max_critic_rounds") or 2)
    # prior_round 为已完成次数；本轮结束后变为 prior_round+1。
    # 仅当本轮结束后仍 < max_rounds 时允许 SUPPLEMENT（即首轮可补）。
    can_supplement = (prior_round + 1) < max_rounds
    evidence = list(state.get("evidence") or [])
    completed = list(state.get("completed_tasks") or [])
    plan = state.get("plan")
    has_kb = bool(state.get("knowledge_base_ids"))
    delta: list[dict[str, Any]] = []

    started = make_event(
        events=events + delta,
        task_id=task_id,
        run_id=run_id,
        event_type="CRITIC_STARTED",
        node="critic_review",
        data={"criticRound": prior_round + 1, "maxCriticRounds": max_rounds},
    )
    delta.append(started)

    if settings.synthetic_allowed():
        critique = _mock_critique(
            evidence=evidence,
            completed_tasks=completed,
            plan=plan,
            can_supplement=can_supplement,
            has_kb=has_kb,
        )
    else:
        if not settings.deepseek_api_key.strip():
            raise RuntimeError("LLM_NOT_CONFIGURED")
        model = get_chat_model(temperature=0.1, timeout_seconds=remaining_seconds(state, 60))
        payload = {
            "query": state.get("clarified_query") or state.get("user_query"),
            "plan": plan,
            "completedTasks": completed,
            "evidence": _evidence_summary(evidence),
            "cannotSupplement": not can_supplement,
            "criticRound": prior_round + 1,
            "maxCriticRounds": max_rounds,
        }
        try:
            resp = model.invoke(
                [
                    SystemMessage(content=_CRITIC_SYSTEM),
                    HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
                ]
            )
            critique = CritiqueResult.model_validate(_extract_json(str(resp.content)))
        except Exception as exc:  # noqa: BLE001 - 协议/模型故障必须终止，禁止伪造质量结论
            raise RuntimeError("CRITIC_RESPONSE_INVALID") from exc

    if not can_supplement:
        critique = _force_terminal_verdict(critique)
    critique = _enforce_verdict_invariants(
        critique,
        evidence=evidence,
        can_supplement=can_supplement,
        has_kb=has_kb,
        plan=plan,
    )

    new_round = prior_round + 1
    completed_event = make_event(
        events=events + delta,
        task_id=task_id,
        run_id=run_id,
        event_type="CRITIQUE_COMPLETED",
        node="critic_review",
        data={
            "verdict": critique.verdict,
            "criticRound": new_round,
            "gapCount": len(critique.gaps),
            "summary": critique.summary,
            "gaps": list(critique.gaps),
            "limitations": list(critique.limitations),
            "maxCriticRounds": max_rounds,
            "supplementTaskCount": len(critique.supplement_tasks),
        },
    )
    delta.append(completed_event)

    return {
        "critique": critique.model_dump(by_alias=True),
        "critic_round": new_round,
        "step_count": step,
        "status": "RUNNING",
        "events": delta,
    }


def supplement_research(state: ResearchState) -> dict[str, Any]:
    """
    将 Critic 的补充任务写入 pending_tasks，供后续 KB/Web 节点消费。

    Returns:
        pending_tasks / events / step_count 增量。
    """
    step, limit_failure = claim_step(state, "supplement_research")
    if limit_failure is not None:
        return limit_failure

    events = list(state.get("events") or [])
    task_id = state["task_id"]
    run_id = state["run_id"]
    critique = state.get("critique") or {}
    has_kb = bool(state.get("knowledge_base_ids"))
    raw_tasks = list(critique.get("supplementTasks") or [])[:2]
    pending: list[dict[str, Any]] = []
    for item in raw_tasks:
        try:
            task = SupplementTask.model_validate(item)
            # 未绑定知识库时丢弃 KB 补充任务，避免空转
            if task.type == "knowledge_research" and not has_kb:
                continue
            pending.append(task.model_dump(by_alias=True))
        except Exception:  # noqa: BLE001 - 跳过非法补充任务
            continue
    if not pending:
        fallback = _default_supplement_tasks(
            has_kb=has_kb,
            reason=str(
                state.get("clarified_query") or state.get("user_query") or "补充检索"
            ),
        )
        pending = [t.model_dump(by_alias=True) for t in fallback]

    delta = [
        make_event(
            events=events,
            task_id=task_id,
            run_id=run_id,
            event_type="SUPPLEMENT_RESEARCH_REQUESTED",
            node="supplement_research",
            data={
                "taskCount": len(pending),
                "taskIds": [t.get("id") for t in pending],
                "criticRound": state.get("critic_round") or 0,
            },
        )
    ]

    return {
        "pending_tasks": pending,
        "step_count": step,
        "status": "RUNNING",
        "events": delta,
    }


def route_after_critic(state: ResearchState) -> str:
    """
    Critic 后路由：失败终止；SUPPLEMENT 且未超轮次 → 补充；否则写报告。

    Returns:
        stop | supplement | write
    """
    if state.get("status") == "FAILED":
        return "stop"
    critique = state.get("critique") or {}
    verdict = critique.get("verdict")
    critic_round = int(state.get("critic_round") or 0)
    max_rounds = int(state.get("max_critic_rounds") or 2)
    # critic_round 已在节点内递增；允许补充当仍有剩余轮次
    if verdict == "SUPPLEMENT" and critic_round < max_rounds:
        return "supplement"
    return "write"
