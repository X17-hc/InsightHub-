"""单任务执行租约，防止新建、恢复或重试并行驱动同一张图。"""

from __future__ import annotations

import threading
import time
import uuid
from contextlib import contextmanager
from typing import Iterator, Protocol

from app.core.config import get_settings


class TaskExecutionConflict(RuntimeError):
    """同一任务已有执行者。"""


class ExecutionLeaseStore(Protocol):
    """执行租约存储接口。"""

    def acquire(self, task_id: str, token: str, ttl_seconds: int, wait_seconds: int) -> bool: ...

    def release(self, task_id: str, token: str) -> None: ...


class InMemoryExecutionLeaseStore:
    """测试使用的线程安全内存租约。"""

    def __init__(self) -> None:
        self._condition = threading.Condition()
        self._leases: dict[str, tuple[str, float]] = {}

    def acquire(self, task_id: str, token: str, ttl_seconds: int, wait_seconds: int) -> bool:
        deadline = time.monotonic() + max(0, wait_seconds)
        with self._condition:
            while True:
                current = self._leases.get(task_id)
                if current is None or current[1] <= time.monotonic():
                    self._leases[task_id] = (token, time.monotonic() + ttl_seconds)
                    return True
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return False
                self._condition.wait(timeout=min(remaining, 0.1))

    def release(self, task_id: str, token: str) -> None:
        with self._condition:
            current = self._leases.get(task_id)
            if current is not None and current[0] == token:
                self._leases.pop(task_id, None)
                self._condition.notify_all()


class RedisExecutionLeaseStore:
    """Redis 原子租约，租约超时后自动释放。

    acquire 使用 SET NX EX，release 通过 compare-and-delete Lua，迟到的旧执行者
    不能删除新执行者的租约。租约当前不续期，因此 TTL 必须覆盖任务 deadline 和
    收尾余量；若以后允许延长任务时长，必须同时增加续租机制。
    """

    _RELEASE_SCRIPT = """
    if redis.call('get', KEYS[1]) == ARGV[1] then
      return redis.call('del', KEYS[1])
    end
    return 0
    """

    def __init__(self, redis_url: str) -> None:
        import redis

        self._client = redis.Redis.from_url(
            redis_url,
            decode_responses=True,
            socket_connect_timeout=2,
            socket_timeout=2,
        )
        self._client.ping()

    @staticmethod
    def _key(task_id: str) -> str:
        return f"ih:task:{task_id}:execution-lease"

    def acquire(self, task_id: str, token: str, ttl_seconds: int, wait_seconds: int) -> bool:
        deadline = time.monotonic() + max(0, wait_seconds)
        while True:
            if self._client.set(self._key(task_id), token, nx=True, ex=max(60, ttl_seconds)):
                return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.1)

    def release(self, task_id: str, token: str) -> None:
        self._client.eval(self._RELEASE_SCRIPT, 1, self._key(task_id), token)


_store: ExecutionLeaseStore | None = None
_store_lock = threading.Lock()


def get_execution_lease_store() -> ExecutionLeaseStore:
    """构造进程级租约客户端；持久化 checkpoint 模式下 Redis 故障时失败关闭。

    内存租约只允许 memory checkpoint 的测试/单进程场景，多 worker 不能依赖它
    防止重复驱动同一张图。
    """
    global _store
    with _store_lock:
        if _store is not None:
            return _store
        settings = get_settings()
        if settings.checkpoint_backend.strip().lower() == "memory":
            _store = InMemoryExecutionLeaseStore()
        else:
            try:
                _store = RedisExecutionLeaseStore(settings.redis_url)
            except Exception as exc:
                raise RuntimeError("Redis execution lease is unavailable") from exc
        return _store


@contextmanager
def hold_task_execution(task_id: str, ttl_seconds: int) -> Iterator[None]:
    """在上下文生命周期内独占任务执行权，并按 token 安全释放。

    该租约覆盖新建、普通恢复和审批恢复；它提供 at-most-one active driver，
    不提供 exactly-once 事件或结果持久化语义。
    """
    store = get_execution_lease_store()
    token = uuid.uuid4().hex
    wait_seconds = max(0, get_settings().execution_lease_wait_seconds)
    if not store.acquire(task_id, token, ttl_seconds, wait_seconds):
        raise TaskExecutionConflict(f"task {task_id} is already running")
    try:
        yield
    finally:
        store.release(task_id, token)


def reset_execution_lease_store_for_tests(store: ExecutionLeaseStore | None = None) -> None:
    """测试辅助：重置或注入执行租约。"""
    global _store
    with _store_lock:
        _store = store
