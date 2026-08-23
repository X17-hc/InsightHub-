"""跨服务事件的序列化和最终响应构造工具。"""

from __future__ import annotations

import json
from typing import Any


SCHEMA_VERSION = "1.0"


def dumps_event(event: dict[str, Any]) -> str:
    """将事件编码为 UTF-8 语义的 NDJSON 文本行。"""
    return json.dumps(event, ensure_ascii=False)


def task_result_line(*,
                     task_id,
                     run_id,
                     status,
                     report_markdown,
                     error,
                     citations=None,
                     plan=None,
                     plan_hash=None,
                     plan_revision=None,
                     quality=None):
    return dumps_event({
        "schemaVersion": "1.0",
        "type": "TASK_RESULT",
        "taskId": task_id,
        "runId": run_id,
        "status": status,
        "plan": plan,
        "planHash": plan_hash,
        "planRevision": plan_revision,
        "reportMarkdown": report_markdown,
        "citations": citations or [],
        "quality": quality,
        "error": error,
    })
