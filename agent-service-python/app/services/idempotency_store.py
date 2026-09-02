"""Redis-backed internal API idempotency state.

The store is intentionally fail-closed: process-local dictionaries diverge across
workers and disappear on restart, which can execute the same task more than once.
"""

from __future__ import annotations

import hashlib
import json
import threading
import uuid
from dataclasses import dataclass
from typing import Any, Protocol

from app.core.config import get_settings


class IdempotencyStoreUnavailable(RuntimeError):
    """Raised when durable idempotency cannot be guaranteed."""


@dataclass(frozen=True)
class ClaimResult:
    acquired: bool
    owner: str | None = None
    state: str | None = None
    response: dict[str, Any] | None = None


class IdempotencyStore(Protocol):
    def claim(self, key: str, ttl_seconds: int) -> ClaimResult: ...

    def complete(self, key: str, owner: str, response: dict[str, Any], ttl_seconds: int) -> None: ...

    def release(self, key: str, owner: str) -> None: ...


def _redis_key(key: str) -> str:
    digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
    return f"ih:agent:idempotency:{digest}"


class RedisIdempotencyStore:
    """Atomic claim/complete state shared by every Agent worker."""

    _COMPLETE = """
    local current = redis.call('get', KEYS[1])
    if not current then return 0 end
    local decoded = cjson.decode(current)
    if decoded['state'] ~= 'RUNNING' or decoded['owner'] ~= ARGV[1] then return 0 end
    redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
    return 1
    """
    _RELEASE = """
    local current = redis.call('get', KEYS[1])
    if not current then return 0 end
    local decoded = cjson.decode(current)
    if decoded['state'] == 'RUNNING' and decoded['owner'] == ARGV[1] then
      return redis.call('del', KEYS[1])
    end
    return 0
    """

    def __init__(self, redis_url: str) -> None:
        import redis

        self._client = redis.Redis.from_url(redis_url, decode_responses=True)
        self._client.ping()

    def claim(self, key: str, ttl_seconds: int) -> ClaimResult:
        owner = uuid.uuid4().hex
        value = json.dumps({"state": "RUNNING", "owner": owner}, separators=(",", ":"))
        try:
            acquired = bool(self._client.set(_redis_key(key), value, nx=True, ex=max(60, ttl_seconds)))
            if acquired:
                return ClaimResult(acquired=True, owner=owner, state="RUNNING")
            current = self._client.get(_redis_key(key))
            parsed = json.loads(current) if current else {}
            response = parsed.get("response") if isinstance(parsed.get("response"), dict) else None
            return ClaimResult(acquired=False, state=str(parsed.get("state") or "RUNNING"), response=response)
        except Exception as exc:  # noqa: BLE001
            raise IdempotencyStoreUnavailable("durable idempotency store is unavailable") from exc

    def complete(self, key: str, owner: str, response: dict[str, Any], ttl_seconds: int) -> None:
        value = json.dumps({"state": "COMPLETED", "response": response}, separators=(",", ":"))
        try:
            updated = self._client.eval(
                self._COMPLETE, 1, _redis_key(key), owner, value, str(max(60, ttl_seconds))
            )
            if int(updated or 0) != 1:
                raise IdempotencyStoreUnavailable("idempotency claim ownership was lost")
        except IdempotencyStoreUnavailable:
            raise
        except Exception as exc:  # noqa: BLE001
            raise IdempotencyStoreUnavailable("durable idempotency store is unavailable") from exc

    def release(self, key: str, owner: str) -> None:
        try:
            self._client.eval(self._RELEASE, 1, _redis_key(key), owner)
        except Exception as exc:  # noqa: BLE001
            raise IdempotencyStoreUnavailable("durable idempotency store is unavailable") from exc


class InMemoryIdempotencyStore:
    """Thread-safe test double; never selected by production application code."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._data: dict[str, dict[str, Any]] = {}

    def claim(self, key: str, ttl_seconds: int) -> ClaimResult:
        del ttl_seconds
        with self._lock:
            current = self._data.get(key)
            if current:
                return ClaimResult(False, state=current["state"], response=current.get("response"))
            owner = uuid.uuid4().hex
            self._data[key] = {"state": "RUNNING", "owner": owner}
            return ClaimResult(True, owner=owner, state="RUNNING")

    def complete(self, key: str, owner: str, response: dict[str, Any], ttl_seconds: int) -> None:
        del ttl_seconds
        with self._lock:
            current = self._data.get(key)
            if not current or current.get("owner") != owner:
                raise IdempotencyStoreUnavailable("idempotency claim ownership was lost")
            self._data[key] = {"state": "COMPLETED", "response": response}

    def release(self, key: str, owner: str) -> None:
        with self._lock:
            if self._data.get(key, {}).get("owner") == owner:
                self._data.pop(key, None)


_store: IdempotencyStore | None = None
_store_lock = threading.Lock()


def get_idempotency_store() -> IdempotencyStore:
    global _store
    with _store_lock:
        if _store is None:
            try:
                _store = RedisIdempotencyStore(get_settings().redis_url)
            except Exception as exc:  # noqa: BLE001
                raise IdempotencyStoreUnavailable("durable idempotency store is unavailable") from exc
        return _store


def reset_idempotency_store_for_tests(store: IdempotencyStore | None = None) -> None:
    global _store
    with _store_lock:
        _store = store
