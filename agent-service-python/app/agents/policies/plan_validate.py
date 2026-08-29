"""新计划业务校验与哈希。不解析历史不可变计划。"""

from __future__ import annotations

import hashlib
import json

from app.schemas.protocol import Plan


def validate_new_plan(plan: Plan, *, has_kb: bool) -> None:
    """校验新 LLM 计划条数、维度与知识库约束。"""
    if not 2 <= len(plan.tasks) <= 8:
        raise ValueError("new plan must contain between two and eight tasks")
    if len(plan.research_dimensions) < 2:
        raise ValueError("new plan must declare at least two research dimensions")
    if not has_kb and all(task.type == "knowledge_research" for task in plan.tasks):
        raise ValueError("knowledge-only plan requires a bound knowledge base")
    if any(len(task.description.strip()) < 12 for task in plan.tasks):
        raise ValueError("plan task must contain a concrete evidence objective")


def canonical_plan_json(plan: Plan) -> str:
    """稳定序列化，供哈希与审批比对。"""
    return json.dumps(
        plan.model_dump(by_alias=True),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def plan_hash(plan: Plan) -> str:
    """计划内容 SHA256。"""
    return hashlib.sha256(canonical_plan_json(plan).encode("utf-8")).hexdigest()
