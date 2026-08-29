"""图节点名。与历史协议 event.node 对齐，避免前端时间线断裂。"""

SUPERVISOR = "supervisor"
PLANNER = "create_plan"
KB_RESEARCHER = "knowledge_research"
WEB_RESEARCHER = "web_research"
VERIFIER = "merge_evidence"
CRITIC = "critic_review"
SUPPLEMENT = "supplement_research"
ANALYST = "data_analysis"
WRITER = "write_report"
FINALIZE = "finalize"

DEFAULT_MAX_REACT_ITERS = 6
MAX_SUBMIT_RETRIES = 2
HANDOFF_LIMIT_MULTIPLIER = 2

