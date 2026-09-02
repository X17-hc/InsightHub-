"""Critic Agent：评审证据与计划覆盖；最多触发一轮补充研究。"""

from __future__ import annotations

import json
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.agents.policies.critique_normalize import (
    default_supplement_tasks,
    enforce_verdict_invariants,
    force_terminal_verdict,
    normalize_critique_payload,
)
from app.agents.policies.json_extract import extract_json_object
from app.graph.deadline import remaining_seconds
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.schemas.protocol import CritiqueResult, SupplementTask

_enforce_verdict_invariants = enforce_verdict_invariants
_default_supplement_tasks = default_supplement_tasks
_normalize_critique_payload = normalize_critique_payload
_force_terminal_verdict = force_terminal_verdict

_CRITIC_SYSTEM = """你是 InsightHub Critic。只输出 JSON，不要 Markdown。
格式：{"verdict":"PASS|SUPPLEMENT|FAIL","summary":"...","gaps":["..."],
"limitations":["..."],"supplementTasks":[{"id":"sup-1","type":"web_research|knowledge_research","description":"..."}]}
规则：
1. 关键结论必须有 verified=true 的证据支撑，否则不得 PASS。
2. 计划任务未覆盖或证据冲突/过期/重复时优先 SUPPLEMENT（最多 2 个补充任务）。
3. 若已是最后一轮（cannotSupplement=true），只能 PASS 或 FAIL，并在 limitations 说明限制。
4. 禁止输出模型原始推理长文或密钥。
"""


def _invoke_real_critique(state: ResearchState, payload: dict[str, Any]) -> CritiqueResult:
    """在任务 deadline 内最多调用两次；协议仍不合格时失败关闭。"""
    for attempt in range(2):
        model = get_chat_model(
            temperature=0.0,
            timeout_seconds=remaining_seconds(state, 60),
        )
        instruction = _CRITIC_SYSTEM
        if attempt:
            instruction += "\n上一次响应未通过 JSON 协议校验。本次必须只返回格式完全匹配的 JSON 对象。"
        try:
            resp = model.invoke(
                [
                    SystemMessage(content=instruction),
                    HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
                ]
            )
        except Exception as exc:  # noqa: BLE001 - 远程 LLM 故障仅允许一次有界重试
            if attempt == 0:
                continue
            raise RuntimeError("LLM_UNAVAILABLE") from exc
        try:
            parsed = extract_json_object(str(resp.content))
            return CritiqueResult.model_validate(normalize_critique_payload(parsed))
        except Exception as exc:  # noqa: BLE001 - 不记录模型原文，避免泄露输入证据
            if attempt == 0:
                continue
            raise RuntimeError("CRITIC_RESPONSE_INVALID") from exc
    raise RuntimeError("CRITIC_RESPONSE_INVALID")


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
            supplement_tasks=default_supplement_tasks(
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


def critic_review(state: ResearchState) -> dict[str, Any]:
    """
    Critic 节点：产出 CritiqueResult，并递增 critic_round。

    PASS 表示证据满足计划来源要求；SUPPLEMENT 表示仍有补充轮次；FAIL 表示研究
    质量未通过但图仍可生成带限制说明的可审计报告。只有 LLM、协议、deadline 等
    执行异常才把任务状态置为 FAILED。模型调用只允许在总 deadline 内进行有界
    重试，非法响应必须先经 schema 和业务不变量清洗。

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
        payload = {
            "query": state.get("clarified_query") or state.get("user_query"),
            "plan": plan,
            "completedTasks": completed,
            "evidence": _evidence_summary(evidence),
            "cannotSupplement": not can_supplement,
            "criticRound": prior_round + 1,
            "maxCriticRounds": max_rounds,
        }
        critique = _invoke_real_critique(state, payload)

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
    将 Critic 的补充任务写入 pending_tasks，供 DAG 执行节点消费。

    每轮最多接收两个有效补充任务；未绑定知识库时过滤 knowledge_research。
    补充轮次由 critic_round/max_critic_rounds 共同限制，不能无限循环。

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
    Critic 后路由：执行失败终止；SUPPLEMENT 且未超轮次则补充；否则写报告。

    返回分支固定为 ``stop``、``supplement``、``write``。质量 FAIL 会走 write，
    这是“执行状态”和“研究质量状态”分离的关键约束。

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
