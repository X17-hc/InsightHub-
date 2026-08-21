"""Data-analysis graph node; it only receives verified, bounded evidence."""
from __future__ import annotations
from typing import Any
from langgraph.config import get_stream_writer
from app.graph.events import make_event
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.services.analysis_sandbox import SandboxUnavailable, run_analysis
from app.services.artifacts import save_all

def _emit(event: dict[str, Any]) -> None:
    try: get_stream_writer()(event)
    except Exception: pass

def data_analysis(state: ResearchState) -> dict[str, Any]:
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
        failed = make_event(events=events + [start], task_id=task_id, run_id=run_id, event_type="SANDBOX_FAILED", node="data_analysis", data={"code": "SANDBOX_UNAVAILABLE", "message": str(exc)})
        _emit(failed); return {"step_count": step, "status": "FAILED", "errors": [{"code": "SANDBOX_UNAVAILABLE", "message": str(exc)}], "events": [start, failed]}
    except Exception as exc:
        failed = make_event(events=events + [start], task_id=task_id, run_id=run_id, event_type="SANDBOX_FAILED", node="data_analysis", data={"code": "SANDBOX_FAILED", "message": str(exc)})
        _emit(failed); return {"step_count": step, "status": "FAILED", "errors": [{"code": "SANDBOX_FAILED", "message": str(exc)}], "events": [start, failed]}
    done = make_event(events=events + [start], task_id=task_id, run_id=run_id, event_type="SANDBOX_COMPLETED", node="data_analysis", data={"artifactCount": len(artifacts), "artifacts": [{"id": x["id"], "title": x["title"], "mimeType": x["mimeType"]} for x in artifacts]})
    _emit(done); return {"step_count": step, "analysis_artifacts": artifacts, "events": [start, done]}
