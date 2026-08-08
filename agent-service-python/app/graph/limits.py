"""研究图步骤预算。"""

from __future__ import annotations

from typing import Any

from app.graph.events import make_event
from app.graph.state import ResearchState


def claim_step(state: ResearchState, node: str) -> tuple[int, dict[str, Any] | None]:
    """占用一个步骤；超过 max_steps 时返回统一失败增量。"""
    step = int(state.get("step_count") or 0) + 1
    raw_max_steps = state.get("max_steps")
    max_steps = 20 if raw_max_steps is None else int(raw_max_steps)
    if step <= max_steps:
        return step, None

    events = list(state.get("events") or [])
    failure = make_event(
        events=events,
        task_id=state["task_id"],
        run_id=state["run_id"],
        event_type="TASK_FAILED",
        node=node,
        data={"code": "MAX_STEPS_EXCEEDED", "stepCount": step, "maxSteps": max_steps},
    )
    return step, {
        "step_count": step,
        "status": "FAILED",
        "errors": [
            {
                "code": "MAX_STEPS_EXCEEDED",
                "message": f"step_count={step} > max_steps={max_steps}",
            }
        ],
        "events": [failure],
    }
