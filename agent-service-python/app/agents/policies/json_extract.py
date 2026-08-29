"""从模型文本中抽取 JSON。Planner / Critic 共用，避免复制粘贴。"""

from __future__ import annotations

import json
import re
from typing import Any


def extract_json_object(text: str) -> dict[str, Any]:
    """提取第一个 JSON 对象。"""
    try:
        data = json.loads(text.strip())
        if isinstance(data, dict):
            return data
    except json.JSONDecodeError:
        pass
    match = re.search(r"\{[\s\S]*\}", text)
    if not match:
        raise ValueError("no JSON object in model output")
    data = json.loads(match.group(0))
    if not isinstance(data, dict):
        raise ValueError("extracted JSON is not an object")
    return data


def extract_json_array(text: str) -> list[dict[str, Any]]:
    """提取 JSON 数组；失败返回空列表。"""
    text = text.strip()
    try:
        data = json.loads(text)
        if isinstance(data, list):
            return list(data)
        if isinstance(data, dict) and "items" in data:
            return list(data["items"])
    except json.JSONDecodeError:
        pass
    match = re.search(r"\[[\s\S]*\]", text)
    if not match:
        return []
    return list(json.loads(match.group(0)))
