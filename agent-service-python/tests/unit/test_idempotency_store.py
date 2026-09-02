from __future__ import annotations

import pytest

from app.services.idempotency_store import IdempotencyStoreUnavailable, InMemoryIdempotencyStore


def test_claim_is_exclusive_and_completed_response_is_replayable() -> None:
    store = InMemoryIdempotencyStore()
    first = store.claim("same-key", 60)
    assert first.acquired and first.owner

    concurrent = store.claim("same-key", 60)
    assert not concurrent.acquired
    assert concurrent.state == "RUNNING"

    response = {"taskId": "task-1", "status": "COMPLETED"}
    store.complete("same-key", first.owner, response, 60)
    replay = store.claim("same-key", 60)
    assert not replay.acquired
    assert replay.state == "COMPLETED"
    assert replay.response == response


def test_only_claim_owner_can_complete_or_release() -> None:
    store = InMemoryIdempotencyStore()
    claim = store.claim("key", 60)
    assert claim.owner

    with pytest.raises(IdempotencyStoreUnavailable):
        store.complete("key", "other-owner", {"status": "FAILED"}, 60)
    store.release("key", "other-owner")
    assert not store.claim("key", 60).acquired

    store.release("key", claim.owner)
    assert store.claim("key", 60).acquired
