"""任务控制字：RUNNING / PAUSED / CANCELLED。

优先 Redis；不可用时降级进程内字典，并周期性重试 Redis（避免永久粘滞）。
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Protocol

from app.core.config import get_settings

logger = logging.getLogger(__name__)

CONTROL_RUNNING = "RUNNING"
CONTROL_PAUSED = "PAUSED"
CONTROL_CANCELLED = "CANCELLED"

_VALID = {CONTROL_RUNNING, CONTROL_PAUSED, CONTROL_CANCELLED}
# Redis 失败后最短重试间隔（秒）
_REDIS_RETRY_SECONDS = 5.0


def control_key(task_id: str) -> str:
    """Redis 控制键。"""
    return f"ih:task:{task_id}:control"


class ControlStore(Protocol):
    """控制字存储接口。"""

    def exists(self, task_id: str) -> bool: ...

    def get(self, task_id: str) -> str: ...

    def set(self, task_id: str, value: str, ttl_seconds: int) -> None: ...


class ControlStoreUnavailable(RuntimeError):
    """生产环境无法保证跨 worker 控制一致性。"""


class InMemoryControlStore:
    """进程内控制字（线程安全，支持简易 TTL）。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # task_id -> (value, expire_at_monotonic | None)
        self._data: dict[str, tuple[str, float | None]] = {}

    def _purge_locked(self, task_id: str) -> None:
        item = self._data.get(task_id)
        if item is None:
            return
        _, exp = item
        if exp is not None and time.monotonic() >= exp:
            self._data.pop(task_id, None)

    def exists(self, task_id: str) -> bool:
        with self._lock:
            self._purge_locked(task_id)
            return task_id in self._data

    def get(self, task_id: str) -> str:
        with self._lock:
            self._purge_locked(task_id)
            item = self._data.get(task_id)
            return item[0] if item else CONTROL_RUNNING

    def set(self, task_id: str, value: str, ttl_seconds: int) -> None:
        if value not in _VALID:
            raise ValueError(f"invalid control value: {value}")
        exp: float | None = None
        if ttl_seconds and int(ttl_seconds) > 0:
            exp = time.monotonic() + int(ttl_seconds)
        with self._lock:
            self._data[task_id] = (value, exp)

    def clear(self, task_id: str) -> None:
        with self._lock:
            self._data.pop(task_id, None)


class RedisControlStore:
    """基于 Redis STRING 的控制字。"""

    def __init__(self, redis_url: str) -> None:
        import redis

        self._client = redis.Redis.from_url(redis_url, decode_responses=True)
        # 连通性探测
        self._client.ping()

    def exists(self, task_id: str) -> bool:
        return bool(self._client.exists(control_key(task_id)))

    def get(self, task_id: str) -> str:
        val = self._client.get(control_key(task_id))
        if val in _VALID:
            return str(val)
        return CONTROL_RUNNING

    def set(self, task_id: str, value: str, ttl_seconds: int) -> None:
        if value not in _VALID:
            raise ValueError(f"invalid control value: {value}")
        ttl = max(60, int(ttl_seconds))
        self._client.set(control_key(task_id), value, ex=ttl)


class ResilientControlStore:
    """
    Redis 优先 + 内存镜像；连接失败时降级，并按间隔重试 Redis。

    set 时双写：保证本进程在 Redis 抖动期间仍能读到刚写入的控制字。
    """

    def __init__(self, redis_url: str, *, allow_memory_fallback: bool = True) -> None:
        self._redis_url = redis_url
        self._allow_memory_fallback = allow_memory_fallback
        self._memory = InMemoryControlStore()
        self._redis: RedisControlStore | None = None
        self._lock = threading.Lock()
        self._next_retry_at = 0.0
        self._ensure_redis()

    def _ensure_redis(self) -> RedisControlStore | None:
        with self._lock:
            if self._redis is not None:
                return self._redis
            now = time.monotonic()
            if now < self._next_retry_at:
                return None
            try:
                self._redis = RedisControlStore(self._redis_url)
                logger.info("ControlStore: Redis connected")
                return self._redis
            except Exception as exc:  # noqa: BLE001
                self._next_retry_at = now + _REDIS_RETRY_SECONDS
                logger.warning("ControlStore: Redis unavailable; retry in %.0fs errorType=%s",
                               _REDIS_RETRY_SECONDS, type(exc).__name__)
                return None

    def _invalidate_redis(self, exc: Exception) -> None:
        with self._lock:
            self._redis = None
            self._next_retry_at = time.monotonic() + _REDIS_RETRY_SECONDS
        logger.warning("ControlStore: Redis error; will retry errorType=%s", type(exc).__name__)

    def _fallback_or_raise(self) -> InMemoryControlStore:
        if not self._allow_memory_fallback:
            raise ControlStoreUnavailable("task control service is unavailable")
        return self._memory

    def exists(self, task_id: str) -> bool:
        redis = self._ensure_redis()
        if redis is not None:
            try:
                if redis.exists(task_id):
                    return True
            except Exception as exc:  # noqa: BLE001
                self._invalidate_redis(exc)
        return self._fallback_or_raise().exists(task_id)

    def get(self, task_id: str) -> str:
        redis = self._ensure_redis()
        if redis is not None:
            try:
                return redis.get(task_id)
            except Exception as exc:  # noqa: BLE001
                self._invalidate_redis(exc)
        return self._fallback_or_raise().get(task_id)

    def set(self, task_id: str, value: str, ttl_seconds: int) -> None:
        if self._allow_memory_fallback:
            self._memory.set(task_id, value, ttl_seconds)
        redis = self._ensure_redis()
        if redis is not None:
            try:
                redis.set(task_id, value, ttl_seconds)
            except Exception as exc:  # noqa: BLE001
                self._invalidate_redis(exc)
                self._fallback_or_raise().set(task_id, value, ttl_seconds)
        else:
            self._fallback_or_raise().set(task_id, value, ttl_seconds)


_memory_store = InMemoryControlStore()
_store: ControlStore | None = None
_store_lock = threading.Lock()


def get_control_store() -> ControlStore:
    """返回可用的 ControlStore（Resilient Redis，失败降级内存）。"""
    global _store
    with _store_lock:
        if _store is not None:
            return _store
        settings = get_settings()
        try:
            _store = ResilientControlStore(
                settings.redis_url,
                allow_memory_fallback=not settings.is_production(),
            )
        except Exception as exc:  # noqa: BLE001
            if settings.is_production():
                raise ControlStoreUnavailable("task control service is unavailable") from exc
            logger.warning("ControlStore: init failed, pure InMemory errorType=%s", type(exc).__name__)
            _store = _memory_store
        return _store


def reset_control_store_for_tests(store: ControlStore | None = None) -> None:
    """测试辅助：重置或注入 ControlStore。"""
    global _store
    with _store_lock:
        _store = store if store is not None else _memory_store
