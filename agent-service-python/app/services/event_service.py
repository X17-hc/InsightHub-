"""跨服务事件的序列化和最终响应构造工具。"""

from __future__ import annotations

import json
from typing import Any


SCHEMA_VERSION = "1.0"


def dumps_event(event: dict[str, Any]) -> str:
    """将事件编码为 UTF-8 语义的 NDJSON 文本行。"""
    return json.dumps(event, ensure_ascii=False)


def task_result_line(
    *,
    task_id: str,
    run_id: str,
    status: str,
    report_markdown: str | None,
    error: dict[str, Any] | None,
    citations: list[dict[str, Any]] | None = None,
) -> str:
    """构造最终 TASK_RESULT 行。"""
    return dumps_event(
        {
            "schemaVersion": SCHEMA_VERSION,
            "type": "TASK_RESULT",
            "taskId": task_id,
            "runId": run_id,
            "status": status,
            "reportMarkdown": report_markdown,
            "citations": citations or [],
            "error": error,
        }
    )
