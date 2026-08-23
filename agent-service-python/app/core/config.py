"""应用配置：从环境变量 / 项目根 .env 加载。"""

from functools import lru_cache
import os
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

    app_env: str = "development"
    llm_model: str = "deepseek:deepseek-v4-flash"
    deepseek_api_key: str = ""
    tavily_api_key: str = ""
    python_agent_port: int = 8000
    default_max_steps: int = 20
    # 为 true 时跳过真实 LLM，使用确定性假数据（单测 / 无 Key 冒烟）
    agent_mock_llm: bool = False
    # 仅供本地显式演示；production 永远禁止合成研究数据。
    allow_synthetic_demo: bool = False
    # MOCK 模式下节点边界停顿（毫秒），便于 pause/cancel 联调；单测设 0
    agent_mock_step_delay_ms: int = 800
    # Redis：任务控制字；不可用时降级进程内内存
    redis_url: str = "redis://127.0.0.1:6379/0"
    default_timeout_seconds: int = 300
    # 内部 API 共享密钥；为空时内部接口拒绝服务
    agent_internal_token: str = ""
    # 单任务执行租约等待时间，防止暂停确认与恢复请求的窄竞态
    execution_lease_wait_seconds: int = 5

    # LangGraph Checkpoint：生产默认持久化到 PostgreSQL；测试可显式设为 memory
    checkpoint_backend: str = "postgres"
    checkpoint_pool_max_size: int = 10

    # PostgreSQL / PGVector（知识库片段）
    postgres_host: str = "127.0.0.1"
    postgres_port: int = 5432
    postgres_db: str = "insighthub_vector"
    postgres_user: str = "insighthub"
    postgres_password: str = ""
    postgres_connect_timeout_seconds: int = 5
    postgres_statement_timeout_ms: int = 30000

    # Python 只允许读取该目录内由 Java 上传的文件；相对路径按仓库根目录解析
    upload_root_dir: str = "backend-java/data/uploads"

    # 数据分析 Sandbox：所有路径均由服务端固定，模型不能覆盖这些配置。
    sandbox_enabled: bool = True
    sandbox_image: str = "insighthub-analysis-sandbox:1.0.0"
    sandbox_timeout_seconds: int = 45
    sandbox_memory_limit: str = "512m"
    sandbox_cpu_limit: float = 1.0
    sandbox_pids_limit: int = 64
    artifact_root_dir: str = "/opt/insighthub/artifacts"
    artifact_max_count: int = 8
    artifact_max_total_bytes: int = 20 * 1024 * 1024

    # Embedding：维度固定 1536；mock 时用确定性伪向量
    embedding_mock: bool = False
    embedding_base_url: str = "https://api.openai.com/v1"
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-3-small"
    embedding_dim: int = 1536

    # 分块默认参数
    chunk_size: int = 500
    chunk_overlap: int = 80

    def is_production(self) -> bool:
        return self.app_env.strip().lower() == "production"

    def synthetic_allowed(self) -> bool:
        return not self.is_production() and (self.agent_mock_llm or self.allow_synthetic_demo)

    def readiness_errors(self) -> list[str]:
        errors: list[str] = []
        if self.is_production() and self.agent_mock_llm:
            errors.append("MOCK_MODE_FORBIDDEN")
        if self.is_production() and not self.deepseek_api_key.strip():
            errors.append("LLM_NOT_CONFIGURED")
        if self.is_production() and not self.tavily_api_key.strip():
            errors.append("SEARCH_NOT_CONFIGURED")
        if self.is_production() and not self.postgres_password.strip():
            errors.append("DATABASE_NOT_CONFIGURED")
        if not self.agent_internal_token.strip():
            errors.append("INTERNAL_TOKEN_NOT_CONFIGURED")
        return errors


@lru_cache
def get_settings() -> Settings:
    """返回缓存的 Settings 单例。"""
    settings = Settings()
    # Secrets must be explicitly present in the process environment. This keeps
    # test isolation reliable and prevents a file-based fallback from silently
    # enabling internal APIs when the deployment omitted the secret.
    if "AGENT_INTERNAL_TOKEN" not in os.environ:
        settings.agent_internal_token = ""
    return settings
