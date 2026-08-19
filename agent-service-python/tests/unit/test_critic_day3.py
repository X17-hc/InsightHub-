"""Day3：Critic、补充研究、证据核验与 Writer 过滤。"""

from __future__ import annotations

import os

import pytest
from pydantic import ValidationError

os.environ["AGENT_MOCK_LLM"] = "true"
os.environ["DEEPSEEK_API_KEY"] = ""
os.environ["CHECKPOINT_BACKEND"] = "memory"

from app.agents.critic import critic_review, route_after_critic, supplement_research
from app.agents.evidence_verifier import RuleEvidenceVerifier, merge_evidence
from app.agents.writer import write_report
from app.graph.builder import reset_graph_for_tests
from app.schemas.protocol import (
    AgentTaskRequest,
    CritiqueResult,
    Evidence,
    TaskConfig,
)
from app.services.runner import run_research_task


@pytest.fixture(autouse=True)
def _reset_graph() -> None:
    reset_graph_for_tests()
    yield
    reset_graph_for_tests()


def test_task_config_critic_fields() -> None:
    cfg = TaskConfig.model_validate({"maxCriticRounds": 2, "enableDataAnalysis": False})
    assert cfg.max_critic_rounds == 2
    assert cfg.enable_data_analysis is False
    with pytest.raises(ValidationError):
        TaskConfig.model_validate({"maxCriticRounds": 3})


def test_critique_result_rejects_extra_and_limits_tasks() -> None:
    result = CritiqueResult.model_validate(
        {
            "verdict": "SUPPLEMENT",
            "summary": "need more",
            "gaps": ["gap-1"],
            "limitations": [],
            "supplementTasks": [
                {"id": "sup-1", "type": "web_research", "description": "补充官方文档"},
                {"id": "sup-2", "type": "knowledge_research", "description": "补充内部资料"},
            ],
        }
    )
    assert len(result.supplement_tasks) == 2
    with pytest.raises(ValidationError):
        CritiqueResult.model_validate(
            {
                "verdict": "PASS",
                "summary": "ok",
                "unexpected": True,
            }
        )


def test_rule_verifier_marks_synthetic_and_duplicates() -> None:
    verifier = RuleEvidenceVerifier(allow_synthetic=False)
    items = verifier.verify(
        [
            {
                "id": "e1",
                "sourceTitle": "A",
                "sourceUri": "https://example.com/a",
                "quotedText": "足够长的摘录内容用于规则核验通过，至少二十个字符以上才算有效",
                "sourceType": "WEB",
            },
            {
                "id": "e2",
                "sourceTitle": "B",
                "sourceUri": "https://example.com/a",
                "quotedText": "重复 URI 应未验证且摘录也足够长一些",
                "sourceType": "WEB",
            },
            {
                "id": "e3",
                "sourceTitle": "Synth",
                "sourceUri": "https://docs.example.com/x",
                "quotedText": "合成证据默认不通过需要足够长度文本",
                "sourceType": "SYNTHETIC",
            },
            {
                "id": "e4",
                "sourceTitle": "Short",
                "sourceUri": "https://example.com/short",
                "quotedText": "太短",
                "sourceType": "WEB",
            },
        ],
        plan=None,
        completed_tasks=[],
    )
    assert items[0].verified is True
    assert items[1].verified is False
    assert items[2].verified is False
    assert items[3].verified is False


def test_default_verifier_synthetic_only_when_mock(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.agents import evidence_verifier as ev_mod
    from app.core.config import get_settings

    get_settings.cache_clear()
    monkeypatch.setenv("AGENT_MOCK_LLM", "false")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "")
    get_settings.cache_clear()
    verifier = ev_mod.default_verifier()
    items = verifier.verify(
        [
            {
                "id": "s1",
                "sourceTitle": "Synth",
                "sourceUri": "https://docs.example.com/x",
                "quotedText": "即使没有 API Key 也不应自动验证合成证据",
                "sourceType": "SYNTHETIC",
            }
        ],
        None,
        [],
    )
    assert items[0].verified is False
    get_settings.cache_clear()
    monkeypatch.setenv("AGENT_MOCK_LLM", "true")
    get_settings.cache_clear()


def test_writer_uses_only_verified_for_conclusions() -> None:
    state = {
        "task_id": "task-writer-filter",
        "run_id": "run-1",
        "user_query": "测试 Writer 过滤",
        "plan": {"title": "过滤报告"},
        "evidence": [
            {
                "id": "v1",
                "sourceTitle": "Verified Doc",
                "sourceUri": "https://example.com/v",
                "quotedText": "已验证结论依据",
                "sourceType": "WEB",
                "verified": True,
            },
            {
                "id": "u1",
                "sourceTitle": "Unverified Doc",
                "sourceUri": "https://example.com/u",
                "quotedText": "不得写入结论",
                "sourceType": "SYNTHETIC",
                "verified": False,
            },
        ],
        "critique": {
            "verdict": "FAIL",
            "limitations": ["证据覆盖不足"],
            "gaps": [],
            "supplementTasks": [],
        },
        "events": [],
        "step_count": 0,
        "max_steps": 20,
        "deadline_at": 9_999_999_999,
        "status": "RUNNING",
    }
    out = write_report(state)  # type: ignore[arg-type]
    report = out["report"]
    assert "已验证结论依据" in report
    assert "不得写入结论" not in report.split("## 限制")[0]
    assert "未通过核验" in report
    assert "证据覆盖不足" in report
    citations = out["citations"]
    assert citations[0]["verified"] is True
    assert any(c["verified"] is False for c in citations)


def test_critic_supplement_then_terminal_fail(monkeypatch: pytest.MonkeyPatch) -> None:
    """首轮无 verified → SUPPLEMENT；第二轮强制终止，不得再次 SUPPLEMENT。"""
    from app.agents import evidence_verifier as ev_mod

    monkeypatch.setattr(
        ev_mod,
        "default_verifier",
        lambda: RuleEvidenceVerifier(allow_synthetic=False),
    )

    request = AgentTaskRequest.model_validate(
        {
            "taskId": "task-critic-supplement",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "触发一轮补充研究",
            "config": {
                "maxSteps": 30,
                "requirePlanApproval": False,
                "enableWebSearch": False,
                "maxCriticRounds": 2,
            },
        }
    )
    result = run_research_task(request, trace_id="trace-supplement")
    types = [e.type for e in result.events]
    assert "CRITIC_STARTED" in types
    assert "CRITIQUE_COMPLETED" in types
    assert "SUPPLEMENT_RESEARCH_REQUESTED" in types
    assert types.count("SUPPLEMENT_RESEARCH_REQUESTED") == 1
    assert result.status == "COMPLETED"
    assert result.report_markdown
    # 第二轮不得再 SUPPLEMENT
    critique_events = [e for e in result.events if e.type == "CRITIQUE_COMPLETED"]
    assert len(critique_events) == 2
    assert critique_events[0].data.get("verdict") == "SUPPLEMENT"
    assert critique_events[1].data.get("verdict") in {"PASS", "FAIL"}
    assert critique_events[1].data.get("verdict") != "SUPPLEMENT"


def test_pass_path_emits_critic_without_supplement() -> None:
    """mock 下 SYNTHETIC 可验证 → PASS，不进入补充。"""
    request = AgentTaskRequest.model_validate(
        {
            "taskId": "task-critic-pass",
            "workspaceId": "workspace-demo",
            "userId": "user-demo",
            "query": "正常 Critic 通过路径",
            "config": {
                "maxSteps": 20,
                "requirePlanApproval": False,
                "enableWebSearch": False,
                "maxCriticRounds": 2,
            },
        }
    )
    result = run_research_task(request, trace_id="trace-pass")
    assert result.status == "COMPLETED"
    types = {e.type for e in result.events}
    assert "CRITIC_STARTED" in types
    assert "CRITIQUE_COMPLETED" in types
    assert "SUPPLEMENT_RESEARCH_REQUESTED" not in types
    assert any(c.get("verified") for c in result.citations)


def test_route_after_critic_helpers() -> None:
    assert route_after_critic({"status": "FAILED"}) == "stop"
    assert (
        route_after_critic(
            {
                "status": "RUNNING",
                "critique": {"verdict": "SUPPLEMENT"},
                "critic_round": 1,
                "max_critic_rounds": 2,
            }
        )
        == "supplement"
    )
    assert (
        route_after_critic(
            {
                "status": "RUNNING",
                "critique": {"verdict": "SUPPLEMENT"},
                "critic_round": 2,
                "max_critic_rounds": 2,
            }
        )
        == "write"
    )
    assert (
        route_after_critic(
            {"status": "RUNNING", "critique": {"verdict": "PASS"}, "critic_round": 1, "max_critic_rounds": 2}
        )
        == "write"
    )


def test_merge_and_supplement_nodes_unit() -> None:
    state = {
        "task_id": "task-merge",
        "run_id": "run-1",
        "user_query": "q",
        "plan": {"tasks": []},
        "evidence": [
            {
                "id": "e1",
                "sourceTitle": "Doc",
                "sourceUri": "https://example.com/d",
                "quotedText": "一段可用于核验的摘录文本内容，需要足够长度才能通过规则",
                "sourceType": "WEB",
                "verified": False,
            }
        ],
        "completed_tasks": [],
        "pending_tasks": [],
        "events": [],
        "step_count": 0,
        "max_steps": 20,
        "deadline_at": 9_999_999_999,
        "status": "RUNNING",
        "critique": {
            "verdict": "SUPPLEMENT",
            "supplementTasks": [
                {"id": "sup-1", "type": "web_research", "description": "补充资料"},
                {"id": "sup-kb", "type": "knowledge_research", "description": "无效 KB"},
            ],
        },
        "critic_round": 1,
        "knowledge_base_ids": [],
    }
    merged = merge_evidence(state)  # type: ignore[arg-type]
    assert merged["verified_evidence_ids"]
    assert merged["evidence"][0]["verified"] is True

    supplemented = supplement_research(state)  # type: ignore[arg-type]
    # 无 KB 时丢弃 knowledge_research，仅保留 web
    assert len(supplemented["pending_tasks"]) == 1
    assert supplemented["pending_tasks"][0]["type"] == "web_research"
    assert any(e["type"] == "SUPPLEMENT_RESEARCH_REQUESTED" for e in supplemented["events"])

    # 无证据且仍可补充 → SUPPLEMENT
    empty_first = {
        **state,
        "evidence": [],
        "critic_round": 0,
        "max_critic_rounds": 2,
        "knowledge_base_ids": [],
    }
    first = critic_review(empty_first)  # type: ignore[arg-type]
    assert first["critique"]["verdict"] == "SUPPLEMENT"
    assert first["critic_round"] == 1

    # 无证据且不可补充 → FAIL
    empty_last = {
        **state,
        "evidence": [],
        "critic_round": 1,
        "max_critic_rounds": 2,
        "knowledge_base_ids": [],
    }
    last = critic_review(empty_last)  # type: ignore[arg-type]
    assert last["critique"]["verdict"] == "FAIL"
    assert last["critic_round"] == 2


def test_pass_forbidden_without_verified() -> None:
    from app.agents.critic import _enforce_verdict_invariants

    forced = _enforce_verdict_invariants(
        CritiqueResult(verdict="PASS", summary="bad pass", gaps=(), limitations=(), supplementTasks=()),
        evidence=[{"id": "e1", "verified": False}],
        can_supplement=True,
        has_kb=False,
    )
    assert forced.verdict == "SUPPLEMENT"
    assert forced.supplement_tasks
    assert all(t.type != "knowledge_research" for t in forced.supplement_tasks)

    terminal = _enforce_verdict_invariants(
        CritiqueResult(verdict="PASS", summary="bad pass", gaps=(), limitations=(), supplementTasks=()),
        evidence=[],
        can_supplement=False,
        has_kb=False,
    )
    assert terminal.verdict == "FAIL"


def test_evidence_model_frozen() -> None:
    ev = Evidence.model_validate(
        {
            "id": "e1",
            "sourceTitle": "T",
            "sourceUri": "https://example.com",
            "quotedText": "q",
            "sourceType": "WEB",
            "verified": True,
        }
    )
    with pytest.raises(ValidationError):
        ev.verified = False  # type: ignore[misc]
