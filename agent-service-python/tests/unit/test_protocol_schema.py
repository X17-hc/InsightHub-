"""协议 Pydantic 模型校验。"""

from app.schemas.protocol import AgentTaskRequest, TaskConfig


def test_agent_task_request_aliases():
    raw = {
        "taskId": "task-1",
        "workspaceId": "ws-1",
        "userId": "u-1",
        "query": "hello",
        "knowledgeBaseIds": [],
        "config": {"maxSteps": 10, "requirePlanApproval": False},
    }
    req = AgentTaskRequest.model_validate(raw)
    assert req.task_id == "task-1"
    assert req.config.max_steps == 10
    assert req.config.require_plan_approval is False


def test_task_config_defaults():
    cfg = TaskConfig()
    assert cfg.max_steps == 20
    assert cfg.enable_web_search is True
