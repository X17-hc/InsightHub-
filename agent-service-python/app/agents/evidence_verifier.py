"""证据验证：策略端口 + 规则实现（Day3）；后续可替换为 LLM 评测。"""

from __future__ import annotations

from typing import Any, Protocol

from app.core.config import get_settings
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.schemas.protocol import Evidence


class EvidenceVerifier(Protocol):
    """证据核验端口：输入原始证据字典，输出带 verified 的 Evidence 列表。"""

    def verify(
        self,
        evidence: list[dict[str, Any]],
        plan: dict[str, Any] | None,
        completed_tasks: list[dict[str, Any]],
    ) -> list[Evidence]:
        """核验证据并返回不可变 Evidence 快照。"""
        ...


class RuleEvidenceVerifier:
    """
    规则核验策略。

    - 非空 quotedText（至少 20 字）+ 有效 sourceTitle/sourceUri → 可标 verified
    - SYNTHETIC 默认未验证（仅 AGENT_MOCK_LLM 明确允许）
    - 重复 URI / 空摘要 / 过短摘录 → 未验证
    """

    _MIN_QUOTE_LEN = 20

    def __init__(self, *, allow_synthetic: bool = False) -> None:
        self._allow_synthetic = allow_synthetic

    def verify(
        self,
        evidence: list[dict[str, Any]],
        plan: dict[str, Any] | None,
        completed_tasks: list[dict[str, Any]],
    ) -> list[Evidence]:
        del plan, completed_tasks  # 规则策略本周不依赖计划细节
        seen_uris: set[str] = set()
        result: list[Evidence] = []
        for raw in evidence:
            item = self._normalize(raw)
            uri = (item.get("sourceUri") or "").strip().lower()
            title = (item.get("sourceTitle") or "").strip()
            quote = (item.get("quotedText") or "").strip()
            source_type = (item.get("sourceType") or "WEB").upper()
            duplicate = bool(uri) and uri in seen_uris
            if uri:
                seen_uris.add(uri)

            verified = False
            quote_ok = len(quote) >= self._MIN_QUOTE_LEN
            if quote_ok and title and not duplicate:
                if source_type == "SYNTHETIC":
                    verified = self._allow_synthetic
                elif source_type == "WEB":
                    verified = bool(uri) and (
                        uri.startswith("http://") or uri.startswith("https://")
                    )
                elif source_type == "KNOWLEDGE":
                    verified = bool(uri) or bool(item.get("documentId") or item.get("chunkId"))
                else:
                    verified = bool(uri)

            result.append(
                Evidence.model_validate(
                    {
                        **item,
                        "verified": verified,
                    }
                )
            )
        return result

    @staticmethod
    def _normalize(raw: dict[str, Any]) -> dict[str, Any]:
        """将节点产出的证据 dict 规范为 Evidence 可校验形状。"""
        return {
            "id": str(raw.get("id") or "ev-unknown"),
            "sourceTitle": raw.get("sourceTitle") or raw.get("title") or "Untitled",
            "sourceUri": raw.get("sourceUri") or raw.get("url") or "",
            "quotedText": raw.get("quotedText") or raw.get("snippet") or "",
            "sourceType": raw.get("sourceType") or "WEB",
            "documentId": raw.get("documentId"),
            "chunkId": raw.get("chunkId"),
            "verified": bool(raw.get("verified")),
        }


def default_verifier() -> RuleEvidenceVerifier:
    """按运行配置构造默认规则核验器。"""
    settings = get_settings()
    # 仅显式 mock 模式允许 SYNTHETIC 通过，避免生产漏配 Key 时假 verified
    return RuleEvidenceVerifier(allow_synthetic=bool(settings.agent_mock_llm))


def merge_evidence(state: ResearchState) -> dict[str, Any]:
    """
    合并并核验证据节点：写回 verified 标志，供 Critic / Writer 消费。

    Returns:
        evidence / verified_evidence_ids / events / step_count 增量。
    """
    step, limit_failure = claim_step(state, "merge_evidence")
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
            node="merge_evidence",
            data={"agent": "EvidenceVerifier"},
        )
    )

    raw_evidence = list(state.get("evidence") or [])
    verified_items = default_verifier().verify(
        raw_evidence,
        state.get("plan"),
        list(state.get("completed_tasks") or []),
    )
    evidence = [item.model_dump(by_alias=True) for item in verified_items]
    verified_ids = [item.id for item in verified_items if item.verified]

    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_COMPLETED",
            node="merge_evidence",
            data={
                "agent": "EvidenceVerifier",
                "verifiedCount": len(verified_ids),
                "total": len(evidence),
            },
        )
    )

    return {
        "evidence": evidence,
        "verified_evidence_ids": verified_ids,
        "step_count": step,
        "status": "RUNNING",
        "events": delta,
    }
