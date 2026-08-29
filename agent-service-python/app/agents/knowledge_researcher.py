"""Knowledge Researcher：从 PGVector 知识库取证。"""

from __future__ import annotations

from datetime import UTC, datetime
import hashlib
from typing import Any

from app.agents.policies.plan_scheduler import KB_TYPES, next_ready_batch
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.tools.kb_retrieve import search_knowledge


def knowledge_research(state: ResearchState) -> dict[str, Any]:
    """
    对 pending knowledge_research 任务检索内部知识库。

    检索内容视为不可信资料，仅作为证据片段，不覆盖系统指令。
    """
    step, limit_failure = claim_step(state, "knowledge_research")
    if limit_failure is not None:
        return limit_failure
    events = list(state.get("events") or [])
    task_id = state["task_id"]
    run_id = state["run_id"]
    workspace_id = state.get("workspace_id") or ""
    kb_ids = list(state.get("knowledge_base_ids") or [])
    delta: list[dict[str, Any]] = []

    delta.append(
        make_event(
            events=events + delta,
            task_id=task_id,
            run_id=run_id,
            event_type="NODE_STARTED",
            node="knowledge_research",
            data={"agent": "KnowledgeResearcher", "kbCount": len(kb_ids)},
        )
    )

    raw_pending = list(state.get("pending_tasks") or [])
    ready = next_ready_batch(
        raw_pending,
        list(state.get("completed_tasks") or []),
        max_parallelism=int(state.get("max_parallelism") or 3),
        type_filter=KB_TYPES,
    )
    pending = list(ready.ready)
    # 仅当完全没有 pending 且绑定了 KB 时兜底检索；补充轮次带 web-only 时不自动插入
    if not pending and kb_ids and not raw_pending:
        pending = [
            {
                "id": "kb-1",
                "type": "knowledge_research",
                "description": state.get("clarified_query") or state.get("user_query") or "",
            }
        ]

    all_evidence = list(state.get("evidence") or [])
    completed = list(state.get("completed_tasks") or [])
    ready_ids = {str(item.get("id")) for item in pending}
    remaining = [item for item in raw_pending if str(item.get("id")) not in ready_ids]

    for sub in pending:
        query = str(sub.get("description") or state.get("user_query") or "")
        delta.append(
            make_event(
                events=events + delta,
                task_id=task_id,
                run_id=run_id,
                event_type="TOOL_CALLED",
                node="knowledge_research",
                data={"tool": "kb_retrieve", "query": query, "knowledgeBaseIds": kb_ids},
            )
        )
        raw = search_knowledge(
            workspace_id=workspace_id,
            knowledge_base_ids=kb_ids,
            query=query,
            top_k=6,
        )
        evidence: list[dict[str, Any]] = []
        for idx, item in enumerate(raw, start=1):
            snippet = str(item.get("snippet") or "").strip()
            document_id = str(item.get("documentId") or "").strip()
            chunk_id = str(item.get("chunkId") or "").strip()
            verified = bool(document_id and chunk_id and len(snippet) >= 20)
            evidence.append(
                {
                    "id": f"ev-kb-{sub.get('id', 'x')}-{idx}",
                    "sourceTitle": item.get("title") or "Knowledge chunk",
                    "sourceUri": item.get("url") or "",
                    "quotedText": snippet,
                    "sourceType": "KNOWLEDGE",
                    "documentId": document_id or None,
                    "chunkId": chunk_id or None,
                    "verified": verified,
                    "verificationStatus": "VERIFIED" if verified else "CANDIDATE",
                    "verificationReason": "KNOWLEDGE_CHUNK_RETRIEVED" if verified else "KNOWLEDGE_IDENTITY_INCOMPLETE",
                    "retrievedAt": datetime.now(UTC).isoformat(),
                    "contentHash": hashlib.sha256(snippet.encode("utf-8")).hexdigest() if snippet else None,
                }
            )
        delta.append(
            make_event(
                events=events + delta,
                task_id=task_id,
                run_id=run_id,
                event_type="TOOL_COMPLETED",
                node="knowledge_research",
                data={"tool": "kb_retrieve", "ok": True, "count": len(evidence)},
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
            node="knowledge_research",
            data={"agent": "KnowledgeResearcher", "addedSourceCount": len(all_evidence) - len(state.get("evidence") or []), "totalSourceCount": len(all_evidence)},
        )
    )

    return {
        "evidence": all_evidence,
        "pending_tasks": remaining,
        "completed_tasks": completed,
        "step_count": step,
        "events": delta,
    }
