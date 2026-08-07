"""LLM 工厂：统一创建聊天模型。"""

from __future__ import annotations

from langchain.chat_models import init_chat_model
from langchain_core.language_models.chat_models import BaseChatModel

from app.core.config import get_settings


def get_chat_model(temperature: float = 0.2, timeout_seconds: float | None = None) -> BaseChatModel:
    """
    创建聊天模型实例。

    Args:
        temperature: 采样温度，规划/研究场景建议偏低。

    Returns:
        LangChain ChatModel。
    """
    settings = get_settings()
    kwargs: dict = {"temperature": temperature}
    if timeout_seconds is not None:
        # ChatDeepSeek/OpenAI 兼容客户端均识别 timeout 参数
        timeout = float(timeout_seconds)
        if timeout <= 0:
            raise TimeoutError("agent task timed out")
        kwargs["timeout"] = timeout
    if settings.deepseek_api_key:
        kwargs["api_key"] = settings.deepseek_api_key
    return init_chat_model(settings.llm_model, **kwargs)
