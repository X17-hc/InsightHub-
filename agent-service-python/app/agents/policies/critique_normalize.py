"""Critic 载荷归一化与硬约束。无 LLM。"""

from __future__ import annotations

from typing import Any

from app.schemas.protocol import CritiqueResult, SupplementTask


def normalize_critique_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """只保留协议字段，容忍大小写偏差。"""
    raw_tasks = payload.get("supplementTasks", payload.get("supplement_tasks", []))
    tasks: list[dict[str, Any]] = []
    if isinstance(raw_tasks, list):
        for item in raw_tasks[:2]:
            if isinstance(item, dict):
                tasks.append(
                    {
                        "id": item.get("id"),
                        "type": item.get("type"),
                        "description": item.get("description"),
                    }
                )
    verdict = payload.get("verdict")
    return {
        "verdict": str(verdict).upper() if verdict is not None else verdict,
        "summary": payload.get("summary") or "",
        "gaps": payload.get("gaps") or [],
        "limitations": payload.get("limitations") or [],
        "supplementTasks": tasks,
    }


def default_supplement_tasks(*, has_kb: bool, reason: str) -> tuple[SupplementTask, ...]:
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


def force_terminal_verdict(result: CritiqueResult) -> CritiqueResult:
    """第二轮禁止 SUPPLEMENT。"""
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


def enforce_verdict_invariants(
    result: CritiqueResult,
    *,
    evidence: list[dict[str, Any]],
    can_supplement: bool,
    has_kb: bool,
    plan: dict[str, Any] | None = None,
) -> CritiqueResult:
    """无足够 verified 证据时禁止 PASS。"""
    verified = [item for item in evidence if item.get("verified")]
    minimum_sources = max(
        3,
        int(((plan or {}).get("sourceRequirements") or {}).get("minVerifiedSources") or 3),
    )
    tasks = list(result.supplement_tasks)
    if not has_kb:
        tasks = [item for item in tasks if item.type != "knowledge_research"]

    verdict = result.verdict
    gaps = list(result.gaps)
    limitations = list(result.limitations)
    summary = result.summary

    if verdict == "PASS" and len(verified) < minimum_sources:
        if can_supplement:
            verdict = "SUPPLEMENT"
            summary = summary or "已核验来源未达到计划要求，请求补充研究。"
            if "unverified_evidence" not in gaps and "missing_evidence" not in gaps:
                gaps.append(
                    "insufficient_verified_sources"
                    if verified
                    else ("unverified_evidence" if evidence else "missing_evidence")
                )
            if not tasks:
                tasks = list(default_supplement_tasks(has_kb=has_kb, reason="补充检索以获取可核验来源"))
        else:
            verdict = "FAIL"
            summary = summary or "已核验来源未达到计划要求，无法通过评审。"
            if "unverified_evidence" not in gaps and "missing_evidence" not in gaps:
                gaps.append(
                    "insufficient_verified_sources"
                    if verified
                    else ("unverified_evidence" if evidence else "missing_evidence")
                )
            limitations = list(dict.fromkeys(limitations + ["报告结论仅供参考，缺乏已验证来源"]))
            tasks = []

    if verdict == "SUPPLEMENT" and not tasks:
        tasks = list(
            default_supplement_tasks(
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
