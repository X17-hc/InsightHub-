"""应用配置：从环境变量 / 项目根 .env 加载。"""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# InsightHub 仓库根目录（agent-service-python 的上一级）
REPO_ROOT = Path(__file__).resolve().parents[3]


class Settings(BaseSettings):
    """Agent 服务运行配置。"""

    model_config = SettingsConfigDict(
        env_file=(REPO_ROOT / ".env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    llm_model: str = "deepseek:deepseek-v4-flash"
    deepseek_api_key: str = ""
    tavily_api_key: str = ""
    python_agent_port: int = 8000
    default_max_steps: int = 20
    # 为 true 时跳过真实 LLM，使用确定性假数据（单测 / 无 Key 冒烟）
    agent_mock_llm: bool = False


@lru_cache
def get_settings() -> Settings:
    """返回缓存的 Settings 单例。"""
    return Settings()
