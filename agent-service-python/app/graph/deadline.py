"""图节点统一的执行截止时间辅助。"""

from __future__ import annotations

import time
from typing import Any


def remaining_seconds(state: dict[str, Any], cap_seconds: float) -> float:
    """返回不超过节点上限的剩余秒数；到期时立即失败。"""
    deadline = float(state.get("deadline_at") or 0)
    if deadline <= 0:
        return max(1.0, cap_seconds)
    remaining = deadline - time.time()
    if remaining <= 0:
        raise TimeoutError("agent task timed out")
    return min(remaining, cap_seconds)
