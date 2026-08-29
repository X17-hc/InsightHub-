"""BoundedReAct 超轮失败。"""

import os

os.environ["AGENT_MOCK_LLM"] = "true"

import pytest

from app.agents.runtime.react import AGENT_OUTPUT_INVALID, BoundedReAct, claim_react_turn
from app.agents.runtime.ports import ReactTurn
from app.agents.runtime.spec import AgentSpec


def test_claim_react_turn_stops_at_max_iters() -> None:
    failure = claim_react_turn(
        {"task_id": "t", "run_id": "r", "events": [], "step_count": 0, "max_steps": 20},
        ReactTurn(iteration=6, max_iters=6, node="planner"),
    )
    assert failure is not None
    assert failure["errors"][0]["code"] == AGENT_OUTPUT_INVALID


def test_mock_react_requires_mock_run() -> None:
    spec = AgentSpec(name="empty", description="no mock")
    with pytest.raises(Exception):
        BoundedReAct().run(spec, {"user_query": "q", "task_id": "t", "run_id": "r"})
