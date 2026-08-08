"""节点截止时间测试。"""

from __future__ import annotations

import pytest

from app.graph import deadline


def test_remaining_seconds_never_extends_deadline(monkeypatch):
    monkeypatch.setattr(deadline.time, "time", lambda: 100.0)

    assert deadline.remaining_seconds({"deadline_at": 100.2}, 60) == pytest.approx(0.2)


def test_expired_deadline_raises(monkeypatch):
    monkeypatch.setattr(deadline.time, "time", lambda: 100.0)

    with pytest.raises(TimeoutError):
        deadline.remaining_seconds({"deadline_at": 99.0}, 60)
