# InsightHub 数据库表结构设计说明

> 依据《InsightHub-多智能体研究与知识生产平台设计文档》第 8、11、12、13、14、15 节整理  
> MySQL：`insighthub`（业务库）  
> PostgreSQL：`insighthub_vector`（向量与证据库）  
> DDL 文件：
> - `deploy/mysql/init/02_schema.sql`
> - `deploy/postgres/init/02_schema.sql`

---

## 1. 总体分工

| 存储 | 职责 | 不存什么 |
| --- | --- | --- |
| **MySQL** | 用户/权限、工作空间、Agent/工作流配置、任务状态机、事件、报告、引用、知识库与文档**元数据**、Token、审计 | 大规模向量、长文本分块正文 |
| **PostgreSQL + PGVector** | 文档片段正文、embedding、全文检索、研究证据、分析产物索引 | 用户密码、JWT、RBAC |
| **Redis** | 缓存、分布式锁、限流、临时事件通道 | 最终任务真相数据（不以 Redis 为唯一存储） |

```text
Vue / Java API
    │
    ├─ MySQL.research_task / task_event / report / citation ...
    │
    └─ Python Agent
           ├─ 读 MySQL 元数据（经 Java 或受控接口）
           └─ 读写 PGVector.document_chunk / research_evidence
```

主键统一采用 `VARCHAR(64)` 业务 ID（与协议中的 `taskId`、`workspaceId` 一致），便于跨服务透传。

---

## 2. MySQL 库表一览（`insighthub`）

| # | 表名 | 说明 |
| --- | --- | --- |
| 1 | `sys_user` | 系统用户 |
| 2 | `sys_refresh_token` | JWT Refresh Token |
| 3 | `workspace` | 工作空间 |
| 4 | `workspace_member` | 工作空间成员与角色 |
| 5 | `model_provider` | 模型供应商 |
| 6 | `model_config` | 模型配置（供 Agent 引用） |
| 7 | `tool_definition` | 工具定义 |
| 8 | `mcp_server` | MCP Server 注册 |
| 9 | `agent_definition` | Agent 定义 |
| 10 | `workflow_definition` | 工作流模板 |
| 11 | `research_task` | 研究任务（业务状态机） |
| 12 | `task_event` | 任务执行事件（SSE） |
| 13 | `task_checkpoint` | Checkpoint 索引 |
| 14 | `knowledge_base` | 知识库元数据 |
| 15 | `document` | 文档元数据 |
| 16 | `report` | 研究报告（多版本） |
| 17 | `citation` | 报告引用 |
| 18 | `token_usage` | Token/成本用量 |
| 19 | `audit_log` | 审计日志 |
| 20 | `evaluation_run` | 评测实验运行 |

---

### 2.1 `sys_user` — 系统用户表

**用途**：登录账号、密码哈希与基础资料；支撑 JWT 鉴权。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | VARCHAR(64) PK | 用户业务主键 |
| username | VARCHAR(64) UK | 登录名，全局唯一 |
| password_hash | VARCHAR(255) | 密码哈希，禁止明文 |
| email | VARCHAR(128) UK | 邮箱 |
| display_name | VARCHAR(64) | 展示名 |
| avatar_url | VARCHAR(512) | 头像 |
| status | TINYINT | 1启用 / 0禁用 / 2待激活 |
| last_login_at | DATETIME | 最近登录 |
| created_at / updated_at | DATETIME | 时间戳 |

---

### 2.2 `sys_refresh_token` — 刷新令牌表

**用途**：Access Token + Refresh Token 方案中的刷新令牌落库与吊销。

| 字段 | 说明 |
| --- | --- |
| id | 记录 ID |
| user_id | 所属用户 |
| token_hash | Refresh Token 哈希 |
| expires_at | 过期时间 |
| revoked | 是否已吊销 |
| created_at | 创建时间 |

---

### 2.3 `workspace` — 工作空间表

**用途**：多租户隔离单元；承载并发任务上限与月度 Token 配额。

| 字段 | 说明 |
| --- | --- |
| id | 工作空间 ID |
| name / description | 名称与描述 |
| owner_id | 所有者用户 ID |
| max_concurrent_tasks | 最大并发研究任务数 |
| monthly_token_quota | 每月 Token 配额 |
| status | 1正常 / 0归档 |
| created_at / updated_at | 时间戳 |

---

### 2.4 `workspace_member` — 工作空间成员表

**用途**：成员与 RBAC 角色（`OWNER` / `ADMIN` / `MEMBER`）。

| 字段 | 说明 |
| --- | --- |
| id | 关系 ID |
| workspace_id + user_id | 唯一成员关系 |
| role | 角色编码 |
| joined_at / updated_at | 时间戳 |

---

### 2.5 `model_provider` — 模型供应商表

**用途**：平台级模型接入（OpenAI、DashScope 等）。密钥只存引用，不存明文。

| 字段 | 说明 |
| --- | --- |
| id | 供应商 ID |
| name | 显示名 |
| provider_type | OPENAI / DASHSCOPE / AZURE / CUSTOM |
| base_url | API Base URL |
| api_key_ref | 环境变量名或密钥服务引用 |
| enabled | 是否启用 |

---

### 2.6 `model_config` — 模型配置表

**用途**：具体模型参数；被 `agent_definition.model_config_id` 引用。`workspace_id` 为空表示平台公共模型。

| 字段 | 说明 |
| --- | --- |
| id | 配置 ID |
| provider_id | 供应商 |
| workspace_id | 工作空间（可空） |
| name / model_name | 配置名 / 实际模型名 |
| model_role | CHAT / EMBEDDING / RERANK |
| temperature / max_tokens | 推理参数 |
| extra_json | 扩展参数 |
| enabled | 是否启用 |

---

### 2.7 `tool_definition` — 工具定义表

**用途**：Web 搜索、抓取、RAG、沙箱、MCP 等工具的配置与启停；敏感工具可标记需审批。

| 字段 | 说明 |
| --- | --- |
| id | 工具 ID |
| workspace_id | 归属（可空=平台公共） |
| name / tool_type | 名称与类型 |
| description | 给 Agent 选择用的说明 |
| config_json | 端点、超时、限制 |
| requires_approval | 是否需人工审批 |
| enabled | 是否启用 |

---

### 2.8 `mcp_server` — MCP Server 注册表

**用途**：平台/工作空间注册外部 MCP 工具服务。

| 字段 | 说明 |
| --- | --- |
| id | Server ID |
| workspace_id | 归属（可空） |
| name | 名称 |
| transport | SSE / STDIO / HTTP |
| endpoint | 端点或启动命令 |
| auth_ref | 鉴权引用 |
| status | ACTIVE / DISABLED / ERROR |

---

### 2.9 `agent_definition` — Agent 定义表

**用途**：Planner / Supervisor / Researcher / Knowledge / Analyst / Critic / Writer 等配置。

| 字段 | 说明 |
| --- | --- |
| id | Agent ID |
| workspace_id | 归属（可空=平台模板） |
| name | 名称 |
| agent_type | PLANNER / SUPERVISOR / WEB_RESEARCHER / KNOWLEDGE / DATA_ANALYST / CRITIC / WRITER |
| runtime | JAVA 或 PYTHON（MVP 以 PYTHON 为主） |
| model_config_id | 绑定模型 |
| prompt_version / system_prompt | Prompt 版本与内容 |
| tool_permissions | 允许工具 JSON |
| enabled / version | 启停与配置版本 |

---

### 2.10 `workflow_definition` — 工作流定义表

**用途**：固定技术调研等多 Agent 流程模板；MVP 可不做拖拽编辑器，仍用 JSON 存图定义。

| 字段 | 说明 |
| --- | --- |
| id | 工作流 ID |
| workspace_id | 归属（可空） |
| name / version | 名称与版本（联合唯一） |
| definition_json | 节点、边、中断点 |
| status | DRAFT / ACTIVE / DEPRECATED |
| description | 说明 |

---

### 2.11 `research_task` — 研究任务表（核心）

**用途**：Java 业务任务状态机主表；与 Python 图状态通过 `trace_id` / `current_run_id` / Checkpoint 对齐。

| 字段 | 说明 |
| --- | --- |
| id | 全局唯一 `taskId` |
| workspace_id / creator_id / workflow_id | 归属与模板 |
| title / query / clarified_query | 标题与问题 |
| plan_json / plan_approved | 结构化计划与人工确认 |
| status | CREATED → PLANNING → WAITING_APPROVAL → RUNNING → … → COMPLETED/FAILED/CANCELLED |
| current_node / progress | 当前节点与进度 |
| config_json | maxSteps、并行度、是否联网等 |
| knowledge_base_ids | 关联知识库列表 |
| trace_id / current_run_id | 可观测与执行轮次 |
| error_code / error_message | 失败信息 |
| started_at / completed_at | 起止时间 |

---

### 2.12 `task_event` — 任务事件表

**用途**：Python 节点事件落库；Java 经 SSE 推送；`(task_id, event_no)` 唯一，支持断线按序号续传。

| 字段 | 说明 |
| --- | --- |
| id | 自增主键 |
| task_id | 任务 ID |
| event_no | 任务内单调序号 |
| run_id / node_run_id / node_name | 执行定位 |
| event_type | TASK_STARTED / PLAN_CREATED / NODE_COMPLETED / REPORT_DELTA 等 |
| payload_json | 事件载荷 |
| created_at | 产生时间 |

**索引**：`uk(task_id, event_no)`，`idx(task_id, created_at)`。

---

### 2.13 `task_checkpoint` — Checkpoint 索引表

**用途**：Java 记录可恢复点；**图状态本体由 Python LangGraph Checkpoint 后端保存**，本表存 `checkpoint_id` 与引用。

| 字段 | 说明 |
| --- | --- |
| id | 业务记录 ID |
| task_id / run_id | 任务与轮次 |
| checkpoint_id | LangGraph Checkpoint ID |
| graph_state_ref | 状态存储键/路径 |
| node_name | 产生节点 |
| created_at | 时间 |

---

### 2.14 `knowledge_base` — 知识库元数据表

**用途**：工作空间内知识库配置；向量数据在 PGVector。

| 字段 | 说明 |
| --- | --- |
| id | 知识库 ID |
| workspace_id | 所属工作空间 |
| name / description | 名称描述 |
| embedding_model | Embedding 模型标识 |
| chunk_strategy | PARENT_CHILD / SEMANTIC / FIXED |
| status | ACTIVE / INDEXING / DISABLED |
| doc_count | 文档数缓存 |
| created_by | 创建人 |

---

### 2.15 `document` — 文档元数据表

**用途**：上传文件与解析/索引状态；**正文分块在** `document_chunk`。

| 字段 | 说明 |
| --- | --- |
| id | 文档 ID |
| knowledge_base_id / workspace_id | 归属 |
| file_name / content_type / file_size | 文件信息 |
| content_hash | 去重哈希 |
| source_uri | 存储路径 |
| parse_status | PENDING / PARSING / INDEXED / FAILED |
| chunk_count | 分块数 |
| error_message | 失败原因 |
| uploaded_by | 上传人 |

---

### 2.16 `report` — 研究报告表

**用途**：Writer 产出；同一任务多版本（`task_id + version` 唯一）。

| 字段 | 说明 |
| --- | --- |
| id | 报告 ID |
| task_id / workspace_id | 归属 |
| version | 版本号 |
| title / summary | 标题与摘要 |
| markdown_content | Markdown 正文 |
| html_uri / pdf_uri | 导出文件 URI |
| status | DRAFT / READY / ARCHIVED |

---

### 2.17 `citation` — 报告引用表

**用途**：结论与来源绑定；`verified` 标记是否通过 Critic/引用校验。

| 字段 | 说明 |
| --- | --- |
| id | 引用 ID |
| report_id / task_id | 归属 |
| citation_no | 报告内编号 `[n]` |
| source_title / source_uri / source_type | 来源信息 |
| document_id / chunk_id | 内部文档与 PG 片段 |
| quoted_text | 摘录 |
| verified | 是否核验通过 |

---

### 2.18 `token_usage` — Token/成本用量表

**用途**：工作空间配额、成本统计、可观测性（设计文档第 15 节）。

| 字段 | 说明 |
| --- | --- |
| workspace_id / task_id / user_id | 归属维度 |
| agent_type / model_name | 调用来源 |
| prompt_tokens / completion_tokens / total_tokens | Token 计数 |
| estimated_cost | 估算成本 |
| trace_id | 链路 ID |
| created_at | 时间 |

---

### 2.19 `audit_log` — 审计日志表

**用途**：成员管理、计划审批、知识库上传等操作审计。

| 字段 | 说明 |
| --- | --- |
| workspace_id / user_id | 操作上下文 |
| action | 动作编码 |
| resource_type / resource_id | 资源定位 |
| detail_json | 详情 |
| ip | 客户端 IP |
| created_at | 时间 |

---

### 2.20 `evaluation_run` — 评测实验运行表

**用途**：离线评测与性能对比结果落库，支撑简历中的可复现指标。

| 字段 | 说明 |
| --- | --- |
| id / name | 实验标识 |
| dataset_ref | 评测集路径或版本 |
| metrics_json | Recall@K、Faithfulness 等 |
| config_json | 实验配置与基线说明 |
| status | PENDING / RUNNING / COMPLETED / FAILED |
| started_at / completed_at | 时间窗 |

---

## 3. PostgreSQL 库表一览（`insighthub_vector`）

| # | 表名 | 说明 |
| --- | --- | --- |
| 1 | `document_chunk` | 文档片段 + embedding + 全文检索 |
| 2 | `research_evidence` | 研究证据对象 |
| 3 | `analysis_artifact` | 数据分析产物索引 |

扩展：`vector`（pgvector **0.8.6**）。

---

### 3.1 `document_chunk` — 文档片段表（核心）

**用途**：RAG 分块存储与混合检索（向量 + 关键词）；`document_id` 对齐 MySQL `document.id`。

| 字段 | 说明 |
| --- | --- |
| id | 片段 ID（= citation.chunk_id） |
| workspace_id | **检索必须过滤**，防越权 |
| knowledge_base_id / document_id | 归属 |
| chunk_index | 文档内序号 |
| parent_chunk_id | 父子分块的父块 |
| content | 片段正文 |
| content_tokens | Token 估算 |
| metadata_json | 章节、作者等 |
| embedding | `vector(1536)`，需与 embedding 模型一致 |
| embedding_model | 模型标识 |
| page_no / loc_start / loc_end | 页码与原文定位 |
| content_tsv | 自动生成的全文检索向量 |
| created_at / updated_at | 时间戳 |

**索引**：

- 唯一：`(document_id, chunk_index)`
- 业务：`(workspace_id, knowledge_base_id)` 等
- GIN：`content_tsv`
- HNSW：`embedding vector_cosine_ops`

> 若 Embedding 维度不是 1536，需修改列类型并重建 HNSW 索引。

---

### 3.2 `research_evidence` — 研究证据表

**用途**：Web / 知识库 / 分析结果的可追溯证据；供 Critic 核验与 Writer 引用组装。可与图状态并行落盘，避免长任务只存在内存。

| 字段 | 说明 |
| --- | --- |
| id | 证据 ID |
| task_id / workspace_id / run_id | 任务上下文 |
| source_type | WEB / KNOWLEDGE / ANALYSIS |
| source_title / source_uri | 来源 |
| document_id / chunk_id | 内部定位（可选） |
| quoted_text | 摘录 |
| credibility | 可信度 0~1 |
| verified | 是否交叉核验通过 |
| metadata_json | 作者、日期、工具轨迹等 |

---

### 3.3 `analysis_artifact` — 分析产物表

**用途**：Data Analyst 沙箱输出的图表/表格文件索引（实体文件在对象存储或任务临时目录）。

| 字段 | 说明 |
| --- | --- |
| id | 产物 ID |
| task_id / workspace_id / run_id | 任务上下文 |
| artifact_type | CHART / TABLE / FILE / NOTE |
| title | 标题 |
| storage_uri | 存储路径 |
| code_ref / stdout_ref | 代码与输出引用 |
| status | SUCCESS / FAILED / TIMEOUT |
| metadata_json | 扩展信息 |

---

## 4. 跨库关联约定

| MySQL | PostgreSQL | 关联方式 |
| --- | --- | --- |
| `document.id` | `document_chunk.document_id` | 同值字符串 ID |
| `knowledge_base.id` | `document_chunk.knowledge_base_id` | 同值 |
| `workspace.id` | `*.workspace_id` | 同值；PG 侧每次查询必带 |
| `citation.chunk_id` | `document_chunk.id` | 同值 |
| `research_task.id` | `research_evidence.task_id` | 同值 |
| `task_checkpoint.checkpoint_id` | LangGraph Checkpoint 后端 | 由 Python 实现，不强制同库 |

**不建跨库外键**（MySQL ↔ PG），由应用层保证一致性。

---

## 5. 应用 DDL

### 5.1 本机 native 模式

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\TEST
.\scripts\start-mysql.ps1

# MySQL
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" `
  -h 127.0.0.1 -P 3306 -u root -p123456 --protocol=tcp `
  insighthub < deploy\mysql\init\02_schema.sql

# PostgreSQL
$env:PGPASSWORD = "123456"
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" `
  -U insighthub -h 127.0.0.1 -p 5432 -d insighthub_vector `
  -f deploy\postgres\init\02_schema.sql
```

或使用：

```powershell
.\scripts\apply-schema.ps1
```

### 5.2 Docker Compose

容器首次初始化时会执行 `deploy/*/init/` 下脚本；若卷已存在需手动进入容器执行 `02_schema.sql`。

---

## 6. 设计取舍说明

1. **相对设计文档第 12 节的增补**：`model_provider` / `model_config`、`tool_definition`、`mcp_server`、`token_usage`、`audit_log`、`sys_refresh_token`、`evaluation_run`，以及 PG 侧 `research_evidence` / `analysis_artifact`，用于支撑鉴权、配额、工具、评测与证据落盘，避免后期大改表。
2. **`research_task` 扩展字段**：`plan_json`、`plan_approved`、`config_json` 等直接服务 HITL 与协议配置。
3. **向量维度固定 1536**：实现期按所选 Embedding 模型调整；换维度必须迁表。
4. **MVP 可先只用核心表**：任务链路最少依赖 `sys_user` → `workspace` → `research_task` → `task_event` → `report` → `citation`，RAG 再加 `knowledge_base` / `document` / `document_chunk`。
