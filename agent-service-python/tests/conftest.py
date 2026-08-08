"""测试运行时隔离配置。"""

from __future__ import annotations

import os

import pytest

# 必须在导入应用模块前选择内存后端，防止单测连接真实 PostgreSQL/Redis。
os.environ["CHECKPOINT_BACKEND"] = "memory"
os.environ["EXECUTION_LEASE_WAIT_SECONDS"] = "0"

from app.core.config import get_settings
from app.graph.builder import reset_graph_for_tests
from app.services.execution_lease import reset_execution_lease_store_for_tests


@pytest.fixture(autouse=True)
def _reset_graph_runtime():
    """每个测试隔离图、checkpoint 和执行租约单例。"""
    get_settings.cache_clear()
    reset_graph_for_tests()
    reset_execution_lease_store_for_tests()
    yield
    reset_graph_for_tests()
    reset_execution_lease_store_for_tests()
    get_settings.cache_clear()
