"""有界 ReAct：每轮检查控制字、超时与步数，禁止无限 tool 空转。

不使用 create_agent(response_format=...)：DeepSeek 会 400。
"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from app.agents.runtime.names import DEFAULT_MAX_REACT_ITERS, MAX_SUBMIT_RETRIES
from app.agents.runtime.ports import ReactTurn, ToolContext
from app.agents.runtime.spec import AgentSpec
from app.core.config import get_settings
from app.core.llm import get_chat_model
from app.graph.deadline import remaining_seconds
from app.graph.limits import claim_step
from app.graph.state import ResearchState
from app.services.control import CONTROL_RUNNING, get_control_store

AGENT_OUTPUT_INVALID = "AGENT_OUTPUT_INVALID"


class AgentOutputInvalid(RuntimeError):
    """submit_* 校验反复失败或未调用工具。"""

    def __init__(self, message: str = "agent did not produce a valid tool result") -> None:
        super().__init__(f"{AGENT_OUTPUT_INVALID}: {message}")


def check_control(task_id: str) -> str:
    """读取控制字；非 RUNNING 时由调用方停止。"""
    return get_control_store().get(task_id)


def claim_react_turn(state: ResearchState, turn: ReactTurn) -> dict[str, Any] | None:
    """占用一步；超步或超轮返回失败增量。"""
    if turn.iteration >= turn.max_iters:
        return {
            "status": "FAILED",
            "errors": [{"code": AGENT_OUTPUT_INVALID, "message": "max react iterations exceeded"}],
        }
    _step, failure = claim_step(state, turn.node)
    return failure


def _use_mock() -> bool:
    settings = get_settings()
    return settings.agent_mock_llm or settings.synthetic_allowed()


class BoundedReAct:
    """专家运行时：mock 走 spec.mock_run；真实模式 bind_tools 有界循环。"""

    def run(self, spec: AgentSpec, state: ResearchState, context: ToolContext | None = None) -> dict[str, Any]:
        """执行一个专家。mock 路径不调用 LLM。"""
        del context
        if _use_mock():
            if spec.mock_run is None:
                raise AgentOutputInvalid(f"{spec.name} has no mock_run")
            return spec.mock_run(state)
        return self._run_llm(spec, state)

    def _run_llm(self, spec: AgentSpec, state: ResearchState) -> dict[str, Any]:
        max_iters = spec.max_react_iters or DEFAULT_MAX_REACT_ITERS
        messages: list[Any] = [
            SystemMessage(content=spec.system_prompt),
            HumanMessage(content=str(state.get("user_query") or "")),
        ]
        invalid_tries = 0
        working = dict(state)
        for iteration in range(max_iters + 1):
            if check_control(str(working.get("task_id") or "")) != CONTROL_RUNNING:
                return {"status": working.get("status") or "RUNNING", "events": []}
            remaining_seconds(working, 60)
            failure = claim_react_turn(working, ReactTurn(iteration, max_iters, spec.name))
            if failure is not None:
                return failure
            model = get_chat_model(temperature=0.1, timeout_seconds=remaining_seconds(working, 60))
            bound = model.bind_tools(list(spec.tools)) if spec.tools else model
            response = bound.invoke(messages)
            messages.append(response)
            tool_calls = getattr(response, "tool_calls", None) or []
            if not tool_calls:
                invalid_tries += 1
                if invalid_tries >= MAX_SUBMIT_RETRIES:
                    raise AgentOutputInvalid("model returned no tool calls")
                messages.append(HumanMessage(content="必须调用工具提交结果或 handoff，不要只输出自然语言。"))
                continue
            for call in tool_calls:
                name = call.get("name") if isinstance(call, dict) else getattr(call, "name", "")
                matched = next((item for item in spec.tools if item.name == name), None)
                if matched is None:
                    invalid_tries += 1
                    continue
                args = call.get("args") if isinstance(call, dict) else getattr(call, "args", {})
                try:
                    result = matched.invoke(args)
                except Exception as exc:  # noqa: BLE001 - 校验失败交回模型
                    invalid_tries += 1
                    messages.append(ToolMessage(content=str(exc), tool_call_id=str(call.get("id") or name)))
                    if invalid_tries >= MAX_SUBMIT_RETRIES:
                        raise AgentOutputInvalid(str(exc)) from exc
                    continue
                if isinstance(result, dict) and result.get("status") == "FAILED":
                    return result
                if isinstance(result, dict):
                    working.update(result)
                    return result
            if invalid_tries >= MAX_SUBMIT_RETRIES:
                raise AgentOutputInvalid("tool calls were not usable")
        raise AgentOutputInvalid("react loop exhausted")
