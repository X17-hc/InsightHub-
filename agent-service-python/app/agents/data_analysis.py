"""Data-analysis graph node; it only receives verified, bounded evidence."""
from __future__ import annotations
import logging
from typing import Any
from langgraph.config import get_stream_writer
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.services.analysis_sandbox import SandboxUnavailable, run_analysis
from app.services.artifacts import save_all

logger = logging.getLogger(__name__)

def _emit(event: dict[str, Any]) -> None:
    """尽力把脱敏 Sandbox 事件写入当前 LangGraph custom stream。

    事件仍会随 State 返回并持久化，因此 custom writer 不可用不应改变业务终态。
    """
    try: get_stream_writer()(event)
    except Exception: pass

def data_analysis(state: ResearchState) -> dict[str, Any]:
    """用经过字段白名单和数量限制的 VERIFIED 证据生成分析产物。

    无可结构化证据是合法的零产物成功；Docker/镜像不可用或容器失败则产生稳定
    SANDBOX 失败事件。节点不得把脚本正文、宿主机路径或完整 stdout 写入事件。
    """
    step, failure = claim_step(state, "data_analysis")
    if failure is not None: return failure
    events, task_id, run_id = list(state.get("events") or []), state["task_id"], state["run_id"]
    start = make_event(events=events, task_id=task_id, run_id=run_id, event_type="SANDBOX_STARTED", node="data_analysis", data={})
    _emit(start)
    evidence = [{key: item.get(key) for key in ("id", "sourceTitle", "sourceUri", "sourceType", "verified", "quotedText")} for item in state.get("evidence", []) if item.get("verified")][:100]
    if not evidence:
        done = make_event(events=events + [start], task_id=task_id, run_id=run_id, event_type="SANDBOX_COMPLETED", node="data_analysis", data={"artifactCount": 0})
        _emit(done); return {"step_count": step, "analysis_artifacts": [], "events": [start, done]}
    try:
        artifacts = run_analysis(task_id=task_id, workspace_id=state["workspace_id"], run_id=run_id, evidence=evidence)
        save_all(artifacts)
    except SandboxUnavailable as exc:
        logger.warning("sandbox unavailable taskId=%s errorType=%s", task_id, type(exc).__name__)
        message = "sandbox runtime is unavailable"
        failed = make_event(events=events + [start], task_id=task_id, run_id=run_id, event_type="SANDBOX_FAILED", node="data_analysis", data={"code": "SANDBOX_UNAVAILABLE", "message": message})
        _emit(failed); return {"step_count": step, "status": "FAILED", "errors": [{"code": "SANDBOX_UNAVAILABLE", "message": message}], "events": [start, failed]}
    except Exception as exc:
        logger.exception("sandbox execution failed taskId=%s errorType=%s", task_id, type(exc).__name__)
        message = "sandbox execution failed"
        failed = make_event(events=events + [start], task_id=task_id, run_id=run_id, event_type="SANDBOX_FAILED", node="data_analysis", data={"code": "SANDBOX_FAILED", "message": message})
        _emit(failed); return {"step_count": step, "status": "FAILED", "errors": [{"code": "SANDBOX_FAILED", "message": message}], "events": [start, failed]}
    done = make_event(events=events + [start], task_id=task_id, run_id=run_id, event_type="SANDBOX_COMPLETED", node="data_analysis", data={"artifactCount": len(artifacts), "artifacts": [{"id": x["id"], "title": x["title"], "mimeType": x["mimeType"]} for x in artifacts]})
    _emit(done); return {"step_count": step, "analysis_artifacts": artifacts, "events": [start, done]}
