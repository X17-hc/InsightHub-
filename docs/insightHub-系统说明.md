# InsightHub 系统说明

> 依据当前代码库 `C:\Users\Dell\Project\Second\Project\Demo` 整理  
> 更新日期：2026-08-25  
> 面向对象：开发、测试、部署与后续维护  
> GitHub：https://github.com/X17-hc/InsightHub-.git

本文描述**当前实现**，不以早期周次演示脚本为准。不记录真实令牌、密码或模型密钥；示例一律使用占位符。

---

## 1. 产品定位

InsightHub 是以**工作空间**为隔离边界的多智能体研究与知识生产平台。用户在工作空间内提交研究问题，系统生成不可变研究计划，经人工确认后执行检索、证据核验、评审与写稿，最终产出带版本号的 Markdown 报告、引用列表，以及可选的数据分析产物。

当前已落地的能力：

| 能力 | 说明 |
| --- | --- |
| 工作空间隔离 | JWT + RBAC；任务、知识库、报告、产物均带 `workspaceId` |
| 人机协同计划 | Planner 产出不可变修订；确认后执行，支持文字修订 |
| 流式执行 | Python 输出 NDJSON；Java 落库后经 SSE 推给前端 |
| 任务控制 | 暂停、恢复、取消、失败/质量不通过后重试 |
| 知识库 RAG | Java 管元数据与上传；Python 解析、切分、嵌入、混合检索 |
| 报告质量 | 引用核验状态、质量评级；合成证据不能当作已验证来源 |
| 分析 Sandbox | 任务开启后在隔离容器内生成受控产物 |
| 前端工作台 | Vue 3 任务创建/详情、计划确认、事件时间线、报告与产物 |

---

## 2. 总体架构

```text
浏览器
  └── Vue 3 前端 :5177
        └── 仅访问 /api（Vite 代理到 Java :8080）
              └── Spring Boot / Java 21
                    ├── MySQL（业务真相：用户、任务、计划、报告、事件）
                    ├── Redis（限流、并发槽、任务控制字、事件 Pub/Sub）
                    └── X-Internal-Token + NDJSON
                          └── FastAPI Agent :8000
                                ├── Redis（控制字、执行租约）
                                ├── PostgreSQL + PGVector（Checkpoint、向量、产物元数据）
                                └── Docker Sandbox（只读输入 / 受控输出）
```

### 2.1 信任边界

- 浏览器**只**打 Java 的 `/api/**`。Python 的 `/internal/v1/**` 不是对外业务 API。
- Java 用 JWT 识别用户，再用工作空间成员关系与任务归属做授权。
- Java 调 Python 时自动附加 `X-Internal-Token`；Python 中间件对 `/internal/v1/**` 做恒定时间比较。未配置令牌时内部接口返回 `503 INTERNAL_AUTH_NOT_CONFIGURED`，不会从意外文件回退启用。
- `/health`、`/health/live`、`/health/ready` 不要求内部令牌，供探针使用。
- Redis、PGVector、Docker Socket 不得向局域网暴露。当前开发拓扑中，仅 Agent 的 `8000` 经防火墙向 Windows 宿主机开放。
- 分析产物由 Java 代理：先校验工作空间与任务，再向 Python 取元数据/文件；MIME、文件名与大小在 Java 侧再次限制。

### 2.2 统一响应信封

除 SSE 与文件下载外，Java 对外 API 返回：

```json
{ "code": 0, "data": {}, "message": "ok" }
```

客户端必须判断业务 `code`，不能只看 HTTP 状态。常见业务码：`40300` 无权限、`40400` 不存在、`40900` 状态冲突。前端把 `PLAN_VERSION_STALE`、`PLAN_GENERATING`、`PLAN_NOT_WAITING` 等冲突映射成可读提示。

---

## 3. 仓库结构

```text
Demo/
├── insightHub-frontend/          # Vue 3 + TypeScript + Vite 前端
├── backend-java/                 # Spring Boot 平台服务
│   ├── src/main/java/com/hechang/insighthub/
│   └── src/main/resources/       # application.yml、Flyway、字体
├── agent-service-python/         # FastAPI + LangGraph Agent
│   ├── app/agents/               # Planner / Researcher / Critic / Writer / Analysis
│   ├── app/api/                  # 内部任务、知识库、产物
│   ├── app/services/             # Runner、Checkpoint、Sandbox
│   └── Dockerfile.sandbox
├── deploy/
│   ├── docker-compose.yml        # 本机可选一键基础设施
│   ├── postgres/init/            # PGVector 初始化
│   └── ubuntu/                   # VM 上的 Compose、systemd、bootstrap
├── docs/                         # 协议、库表、本文档
├── scripts/                      # Windows 辅助脚本
├── pyproject.toml + uv.lock      # uv workspace 根定义（部署 Agent 必须一并发布）
└── .env.example                  # 不含真实机密的环境变量模板
```

根目录 `pyproject.toml` 与 `uv.lock` 属于 uv workspace。部署 Agent 时不能只复制 `agent-service-python/`，安装依赖时必须选择包 `insighthub-agent-service`，否则会漏装 `psycopg`、`redis`、`langgraph-checkpoint-postgres` 等运行时依赖。

---

## 4. 当前运行拓扑

开发环境按「Windows 平台 + Ubuntu Agent」拆分：

| 组件 | 位置 | 端口 / 绑定 |
| --- | --- | --- |
| Vue 前端 | Windows | `5177`，`/api` 代理到本机 `8080` |
| Java 平台 | Windows | `8080` |
| MySQL | Windows | `127.0.0.1:3306`，库 `insighthub` |
| Python Agent | Ubuntu VM | `8000`（对 Windows 宿主机开放） |
| Redis | Ubuntu Docker | 仅 `127.0.0.1:6379` |
| PostgreSQL + PGVector | Ubuntu Docker | 仅 `127.0.0.1:5432` |
| 分析 Sandbox 镜像 | Ubuntu | `insighthub-analysis-sandbox:1.0.0` |

Java 的 `AGENT_BASE_URL` 必须显式指向当前 Agent，例如 `http://<ubuntu-ip>:8000`。`application.yml` 中该值默认为空；未配置时拒绝启动，避免回退到旧虚拟机地址。Windows **用户环境变量**若仍是旧 IP，必须改完后重启 IntelliJ / 终端，新 JVM 才会继承。

Windows 上的 Java 若仍指向本机 Redis，而任务控制字写在 Ubuntu Redis，会出现「前端已操作、Agent 看不到控制字」。当前开发约定：Java 与 Agent 的 Redis 地址应对齐实际控制面所在位置；不要假设两边自动共用同一实例。

---

## 5. 前端

### 5.1 技术栈

| 项 | 实现 |
| --- | --- |
| 框架 | Vue 3 + TypeScript + Vite 6 |
| 状态 / 路由 | Pinia、Vue Router 4 |
| UI | Ant Design Vue 4、Lucide 图标；部分岛屿使用 `performative-ui` |
| HTTP | Axios，`baseURL=/api`，超时 30s |
| 开发端口 | `5177`（`vite.config.ts` 绑定 `0.0.0.0`） |

启动：

```powershell
cd C:\Users\Dell\Project\Second\Project\Demo\insightHub-frontend
npm install
npm run dev
```

### 5.2 路由

| 路径 | 页面 | 说明 |
| --- | --- | --- |
| `/login`、`/register` | 登录 / 注册 | `meta.public` |
| `/` | 首页重定向 | 进入当前工作空间任务列表 |
| `/workspaces/:workspaceId/tasks` | 任务列表 | |
| `/workspaces/:workspaceId/tasks/new` | 创建任务 | 研究问题、知识库、是否生成分析产物 |
| `/workspaces/:workspaceId/tasks/:taskId` | 任务详情 | 计划、执行动态、报告、引用、产物 |
| `/workspaces/:workspaceId/knowledge` | 知识库 | 上传、文档状态、重建索引 |
| `/workspaces/:workspaceId/settings` | 工作空间设置 | 成员与基础信息 |

### 5.3 鉴权与会话

- Access Token 放在 `Authorization: Bearer`；过期后用 Refresh Token 调 `/api/v1/auth/refresh`。
- 刷新失败跳转 `/login?redirect=...`。
- SSE 是唯一允许用查询参数 `access_token` 的兼容例外，其余接口走请求头。

### 5.4 任务详情行为

`TaskDetailPage.vue` 首屏并行加载任务、计划、事件、报告、引用与产物，再连接 SSE。

- `PLAN_CREATED` / `APPROVAL_REQUIRED`：切到计划页并重新拉取当前计划。
- `PLAN_REVISED`：清空当前计划，展示「正在生成」。
- `REPORT_DELTA`：按 `sequence` 追加流式正文。
- 确认计划时提交 `expectedRevision`；若返回 `PLAN_VERSION_STALE`，提示「计划已更新，请确认最新版本」并刷新计划。
- `PLAN_GENERATING` 或「plan not found」视为生成中的正常空态，不弹失败。

---

## 6. Java 平台服务

### 6.1 技术栈

| 类别 | 实现 |
| --- | --- |
| 运行时 | JDK 21、Spring Boot 3.3.5 |
| Web | Spring MVC；`WebClient` 调 Agent |
| 持久化 | MyBatis-Flex 1.11.8、MySQL、Flyway |
| 安全 | Spring Security、JJWT |
| 协作 | Spring Data Redis、Redisson |
| 文档 | springdoc / Knife4j（`/doc.html`） |
| 报告导出 | CommonMark、OpenHTMLtoPDF / PDFBox |

包根：`com.hechang.insighthub`。

```text
controller     HTTP 绑定、校验、SSE / 文件响应
service        对外业务接口
service.impl   编排、权限、事务、状态机、外部协作
mapper         实体映射、CAS、行锁、Outbox、去重
model.dto / entity / enums
integration    Agent 请求体与 NDJSON 客户端
security       JWT 过滤器
redis          限流、并发槽、控制字、SSE 跨实例
```

普通 CRUD 可用 MyBatis-Flex `ServiceImpl`。必须原子的操作写在 Mapper：任务状态 CAS、`FOR UPDATE`、Outbox 领取、事件去重、跨表 JOIN。

### 6.2 安全与授权

1. `JwtAuthFilter` 解析 Bearer Access Token，建立当前用户。
2. Controller 只调 Service，不直接碰 Mapper。
3. `WorkspaceAccessService` 校验成员资格；角色为 `OWNER` / `ADMIN` / `MEMBER`。暂停/恢复/取消/重试：任务创建人或空间管理员。计划确认与修订：**仅创建人**。
4. 所有工作空间资源查询都带 `workspaceId`，禁止只凭资源 ID 跨空间读取。

### 6.3 任务状态机

```text
CREATED → PLANNING → WAITING_APPROVAL → RUNNING
                         ↑                    │
                         └── PLAN（修订）      ├── PAUSING → PAUSED → RUNNING
                                              ├── REVIEWING
                                              ├── GENERATING → COMPLETED
                                              ├── FAILED → RUNNING（重试）
                                              └── CANCELLED
```

允许的迁移见 `TaskStateMachine`。非法迁移抛 `INVALID_STATUS_TRANSITION`（409）。

| 状态 | 含义 |
| --- | --- |
| `CREATED` | 已落库，尚未进入规划 |
| `PLANNING` | Planner 正在生成或修订计划 |
| `WAITING_APPROVAL` | 有 PENDING 修订，等待确认 |
| `RUNNING` | 执行研究 / 重试后的规划流 |
| `PAUSING` / `PAUSED` | 暂停中 / 已暂停 |
| `REVIEWING` | Critic 评审 |
| `GENERATING` | Writer 出报告 |
| `COMPLETED` / `FAILED` / `CANCELLED` | 终态 |

条件更新使用 `updateStatusIfCurrent`、`failDispatchIfCurrentRun` 等，避免旧 run 覆盖新重试。

### 6.4 与 Agent 的两种启动方式

1. **直接流式**（创建任务、失败重试）：`TaskExecutionServiceImpl.executeStream` → `AgentStreamClient.streamTask`。
2. **Outbox 派发**（确认计划后的 EXECUTE、文字修订后的 PLAN）：写入 `task_dispatch_outbox`，后台 Worker 再调 Agent。事务只持久化状态与 Outbox，远程流不在同一事务里跑。

请求体由 `AgentTaskRequestFactory` 统一构造，避免同步接口与 NDJSON 接口字段漂移。关键字段：`phase`（`PLAN` / `EXECUTE`）、`planRevision`、`runId`、`revisionInstruction`、`knowledgeBaseIds`、`config.nextEventId`、`config.requirePlanApproval`。创建任务默认修订 1；重试传入 `max+1` 和新 `runId`。

### 6.5 事件落库与推送

1. Agent 每行一条 NDJSON。
2. Java 按 `(task_id, event_no)` 幂等插入 `task_event`（`insertIgnoreDuplicate`）。
3. `TaskEventSideEffectHandler` 根据事件类型改任务状态或落计划。
4. Redis Pub/Sub + `TaskEventSseHub` 推给本机 SSE 连接。
5. 前端可用 `Last-Event-ID` 或 `fromEventNo` 补读；语义是 **at-least-once**，必须按 `eventId` 去重。

副作用失败（例如计划落库异常）**不能**再当成「坏 NDJSON」跳过，否则时间线里有 `PLAN_CREATED`、库里却没有新修订。

---

## 7. 研究计划（人机协同）

计划是不可变修订，表 `task_plan_revision`，唯一键 `uk_task_plan_revision (task_id, revision_no)`。

修订状态：`PENDING` → `APPROVED` 或 `SUPERSEDED`。

### 7.1 首次生成

1. 创建任务：`CREATED → PLANNING`，启动 PLAN 流，`planRevision = 1`。
2. Agent 发出 `PLAN_CREATED`（含 `plan`、`planHash`、`planRevision`）。
3. `PlanApplicationServiceImpl.recordPlannerResult` 插入 PENDING 修订，并把任务投影更新为 `WAITING_APPROVAL`。

### 7.2 确认与修订

- **确认**：仅创建人；任务必须是 `WAITING_APPROVAL`；`expectedRevision` 必须等于当前 PENDING，且修订 id 等于 `research_task.current_plan_revision_id`。成功后修订变 `APPROVED`，任务变 `RUNNING`，Outbox 派发 `EXECUTE`（带 `approvedPlanHash`）。
- **修订**：把当前 PENDING 标为 `SUPERSEDED`，清空任务上的当前计划指针，状态回到 `PLANNING`，Outbox 派发下一修订号的 `PLAN`，并带上用户文字意见。
- 版本不匹配返回 `PLAN_VERSION_STALE`。

### 7.3 失败重试与修订号（当前实现）

失败，或 `COMPLETED` 且质量为 `FAIL` / `LEGACY_SYNTHETIC` 时可以重试。重试会**整图重跑 PLAN**，历史修订行不删除。

当前代码约定：

1. `ResearchTaskServiceImpl.nextPlanRevisionNo`：`max(revision_no) + 1`（无历史则为 1）。该号随 `executeStream(..., runId, planRevision)` 传给 Agent，请求体不再写死修订 1。
2. `prepareRetry` 只清错误、质量字段和新 `runId`，**不**清空 `current_plan_revision_id`。
3. `recordPlannerResult` 在任务行锁下按库内最新修订分配号：`allocatedRevision = latest == null ? 1 : latest.revisionNo + 1`。**落库修订号以数据库为准**，不采用 Agent 事件里的 `planRevision`。
4. `current` 在任务为 `PLANNING` 时返回 `PLAN_GENERATING`；否则在 `PENDING`/`APPROVED` 中取最大 `revision_no`。

因此：重试后会出现新的 PENDING（例如修订 3）。确认时必须带上该新修订号。若界面仍显示已批准的旧修订并提交 `expectedRevision`，会得到 `PLAN_VERSION_STALE`。刷新计划后再确认。

---

## 8. Python Agent

### 8.1 运行方式

- FastAPI + LangGraph。
- 生产由 systemd 单元 `insighthub-agent` 拉起；开发可用 `uv run uvicorn app.main:app --host 0.0.0.0 --port 8000`。
- 配置来自环境变量 / `EnvironmentFile`。内部令牌必须在**进程环境**中，不能只靠工作目录里的偶然文件。

健康检查：`GET /health`、`/health/live`、`/health/ready`。开发文档：`/docs`（不要求内部令牌）。

### 8.2 工作流

Python 侧是 **Supervisor + Handoff**：LLM/mock 主管只决定下一个专家，专家各自执行后回到主管。审批、规则核验、finalize 仍是系统闸门，不是 Agent。

```text
Supervisor
  → Planner → [HITL 审批]
  → Knowledge / Web Researcher（按计划 DAG）
  → Evidence Verifier（规则，不可跳过）
  → Critic
       ├─ SUPPLEMENT → 补充任务回 Supervisor 再研究
       ├─ FAIL verdict → Writer（带限制）
       └─ PASS → [enableDataAnalysis ? Data Analysis : 跳过] → Writer → finalize
```

- `requirePlanApproval=true` 时，PLAN 阶段停在审批，等 Java 调 `/plan/approve` 或带修订意见的新 PLAN。
- Critic 最多两轮。
- 生产默认 PostgreSQL Checkpoint；测试可切 memory。
- 重试时 Java 传入 `config.nextEventId`，Agent 清旧图状态并从该号续编事件。
- 流式接口按行输出 NDJSON，最后一行 `TASK_RESULT`。

### 8.3 知识库与 RAG

Java 维护知识库/文档元数据与上传文件；Python 负责解析、分块、嵌入、PGVector 存储与混合检索。检索始终带 `workspaceId` 与知识库 ID 列表。

文档状态：`PENDING → PARSING → INDEXED | FAILED`。上传事务提交后再异步通知 Python 入库，避免读到未提交行。

### 8.4 数据分析 Sandbox

仅当创建任务时 `enableDataAnalysis=true`。节点执行**固定**证据汇总脚本（不是让模型任意写 Python）：产出 CSV、JSON，有证据时再出 PNG。最多取 100 条已验证证据，字段白名单：`id`、`sourceTitle`、`sourceUri`、`sourceType`、`verified`、`quotedText`。无证据时 `SANDBOX_COMPLETED` 且 `artifactCount=0`，任务不因此失败。

| 约束 | 实现 |
| --- | --- |
| 网络 | `--network=none` |
| 身份 | `65534:65534` |
| 文件系统 | `--read-only`；仅 `/input:ro`、`/output:rw` |
| 提权 | `no-new-privileges`、`--cap-drop=ALL` |
| 产物 | CSV / JSON / Parquet / PNG / SVG；最多 8 个、合计 20 MiB |

Docker 或镜像不可用时发 `SANDBOX_UNAVAILABLE`。对外错误不暴露 Docker 命令、宿主路径或密钥。

### 8.5 Mock 与真实模式

| 变量 | 作用 |
| --- | --- |
| `AGENT_MOCK_LLM=true` | 确定性假 LLM，无 DeepSeek Key 时可跑通链路 |
| `EMBEDDING_MOCK=true` | 确定性伪向量，无 Embedding Key 时可入库/检索 |

生产研究必须 `AGENT_MOCK_LLM=false`。真实模式禁止把合成网页当证据；Tavily 等检索失败会导致研究步骤失败，后续依赖步骤 `SKIPPED_DEPENDENCY_FAILED`。Mock 结果不得当作真实研究结论。

---

## 9. 对外与内部 API

基础路径前缀：`/api/v1`。Knife4j：`http://127.0.0.1:8080/doc.html`。

### 9.1 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/register` | 注册 |
| POST | `/auth/login` | 登录 |
| POST | `/auth/refresh` | 刷新 Access Token |
| GET | `/auth/me` | 当前用户 |
| GET | `/health` | Java 存活 |

演示账号（`DEMO_SEED_ENABLED=true` 时）：`demo` / `demob`，密码见种子数据约定；演示空间 `workspace-demo`、`workspace-demo-b`。

### 9.2 工作空间与 Agent 定义

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST / GET | `/workspaces` | 创建、列出 |
| GET | `/workspaces/{workspaceId}` | 详情 |
| GET / POST | `/workspaces/{workspaceId}/members` | 成员 |
| DELETE | `/workspaces/{workspaceId}/members/{userId}` | 移除成员 |
| GET / POST | `/workspaces/{workspaceId}/agents` | Agent 定义 |
| PUT | `.../agents/{agentId}` | 更新 |
| POST | `.../agents/{agentId}/enable`、`/disable` | 启停 |

### 9.3 知识库

前缀：`/workspaces/{workspaceId}/knowledge-bases`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST / GET | `/` | 创建、列表 |
| GET / DELETE | `/{kbId}` | 详情、禁用/删除 |
| POST | `/{kbId}/documents` | multipart 上传（Spring 单文件上限 6MB，业务上限约 5MB） |
| GET | `/{kbId}/documents`、`/{kbId}/documents/{docId}` | 文档 |
| POST | `/{kbId}/documents/{docId}/reindex` | 重建索引 |

### 9.4 研究任务

前缀：`/workspaces/{workspaceId}/research/tasks`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/` | 异步创建，HTTP 202 |
| POST | `/sync` | 同步执行（兼容早期演示） |
| GET | `/`、`/{taskId}` | 列表、详情 |
| DELETE | `/{taskId}` | 仅终态任务，级联删除任务数据 |
| GET | `/{taskId}/event-records` 或 `/event-log` | 历史事件 JSON |
| GET | `/{taskId}/events` | SSE |
| POST | `/{taskId}/pause`、`/resume`、`/cancel`、`/retry` | 控制面 |
| GET | `/{taskId}/plan`、`/{taskId}/plans` | 当前计划、历史 |
| POST | `/{taskId}/plan/approve`、`/plan/revise` | 确认、文字修订 |
| GET | `/{taskId}/report`、`/reports`、`/reports/{version}` | 最新报告、版本 |
| GET | `/{taskId}/reports/{version}/exports/html`、`/pdf` | 下载（非信封，直接附件） |
| GET | `/{taskId}/citations` | 最新报告引用（兼容） |
| GET | `/{taskId}/reports/{version}/citations` | 指定版本引用 |
| GET | `/{taskId}/artifacts` | 产物元数据 |
| GET | `/{taskId}/artifacts/{artifactId}/content` | `disposition=inline\|attachment` |

### 9.5 Java → Python 内部接口

均需 `X-Internal-Token`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/internal/v1/agent/tasks` | 同步任务 |
| POST | `/internal/v1/agent/tasks/stream` | NDJSON 流 |
| POST | `/internal/v1/agent/tasks/{taskId}/resume` | Checkpoint 恢复 |
| POST | `/internal/v1/agent/tasks/{taskId}/plan/approve` | 批准后继续 |
| POST | `/internal/v1/knowledge/documents/ingest` | 入库 |
| POST | `/internal/v1/knowledge/retrieve` | 混合检索 |
| POST | `/internal/v1/knowledge/chunks/delete-by-kb` | 按知识库删片段 |
| GET | `/internal/v1/agent/tasks/{taskId}/artifacts` | 产物元数据 |
| GET / HEAD | `.../artifacts/{artifactId}/content` | 读产物文件 |

流式请求体核心字段见 `docs/protocol.md`。Java 侧由 `AgentTaskRequestFactory` 保证 `maxSteps=20`、`maxParallelism=3`、`maxCriticRounds=2`、`enableWebSearch=true`。

---

## 10. 数据存储

### 10.1 MySQL（业务事实来源）

Flyway：`backend-java/src/main/resources/db/migration`

| 迁移 | 内容 |
| --- | --- |
| `V1__baseline_schema.sql` | 用户、工作空间、Agent、任务、事件、知识库、报告、引用、审计等 |
| `V2__day1_plan_revision.sql` | `task_plan_revision`、`research_task.current_plan_revision_id` |
| `V3__day2_plan_dispatch_outbox.sql` | `task_dispatch_outbox` |
| `V4__research_task_analysis_flag.sql` | `enable_data_analysis` |
| `V5__research_quality_and_source_provenance.sql` | 质量字段、引用核验与来源溯源、计划批准备注 |

关键表：

| 表 | 作用 |
| --- | --- |
| `sys_user` / `sys_refresh_token` | 账号与刷新令牌 |
| `workspace` / `workspace_member` | 空间与角色 |
| `research_task` | 状态机主表：run、计划指针、质量计数、错误 |
| `task_plan_revision` | 不可变计划修订；`(task_id, revision_no)` 唯一 |
| `task_event` | 事件；`(task_id, event_no)` 唯一 |
| `task_dispatch_outbox` | 可靠派发 |
| `task_checkpoint` | Checkpoint 索引（删除任务时先清） |
| `report` | 不可变报告版本；`(task_id, version)` 唯一 |
| `citation` | 挂在 `report_id` 上，含 `verification_status` |
| `knowledge_base` / `document` | 知识库与上传元数据（正文不在 MySQL） |
| `audit_log` | 计划确认/修订等审计 |

`scripts/apply-schema.ps1` **只用于空库首次初始化**，不能替代 Flyway 升级。

### 10.2 PostgreSQL + PGVector

库名默认 `insighthub_vector`。初始化 SQL：`deploy/postgres/init/02_schema.sql`。

| 表 | 用途 |
| --- | --- |
| `document_chunk` | 文档分块、`vector(1536)`、全文 `content_tsv`、HNSW；混合检索 |
| `research_evidence` | Web / 知识库 / 分析证据缓存，供 Critic、Writer |
| `analysis_artifact` | Sandbox 产物元数据（`storage_uri` 仅 Python 内部解析，不返回浏览器） |

LangGraph Checkpoint 生产默认写 PostgreSQL（`CHECKPOINT_BACKEND=postgres`）。

### 10.3 Redis

- 工作空间并发槽、创建限流
- 任务控制字（RUNNING / PAUSED / CANCELLED）
- 事件 Pub/Sub
- Agent 侧执行租约

不以 Redis 作为最终任务真相。

### 10.4 文件目录

| 位置 | 用途 |
| --- | --- |
| Java `UPLOAD_ROOT_DIR`（默认 `./data/uploads`） | 知识库原文 |
| Ubuntu `/opt/insighthub/agent` | 发布副本与 `.venv` |
| Ubuntu `/opt/insighthub/artifacts` | Sandbox 输入输出 |
| Ubuntu `/etc/insighthub/agent.env` | Agent 机密（进程环境注入） |

---

## 11. 报告质量

落库时 `TaskResultServiceImpl` 会校正质量，而不是盲目相信 Agent 自称的 PASS：

- `SYNTHETIC` 引用不得保持 `VERIFIED`。
- 自称 `PASS` 但已验证来源少于 3 条，或存在合成引用时，报告质量降为 `FAIL`，报告状态为 `LIMITED`。
- 任务与报告上同步 `quality_status`、`quality_summary`、已验证/总引用数。
- `FAILED`，或 `COMPLETED` 且质量为 `FAIL` / `LEGACY_SYNTHETIC`，允许重试。

引用核验状态：`CANDIDATE`、`VERIFIED`、`SYNTHETIC` 等；并保存 `canonical_uri`、`final_uri`、`content_hash`、`http_status`、`retrieved_at`。

---

## 12. 配置（只列变量名）

| 变量 | 使用方 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | Java | 至少 32 字节；空则拒启 |
| `AGENT_BASE_URL` | Java | Agent 根地址，必须显式配置 |
| `AGENT_INTERNAL_TOKEN` | Java + Agent | 两端必须相同 |
| `MYSQL_JDBC_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | Java | 业务库 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | Java（及本机开发） | 控制面 |
| `REDIS_URL` | Agent | 控制字与租约 |
| `POSTGRES_*` / `POSTGRES_URL` | Agent / Compose | 向量与 Checkpoint |
| `JAVA_SERVER_PORT` | Java | 默认 8080 |
| `TASK_TIMEOUT_SECONDS` | Java | 默认 900 |
| `TASK_CREATE_RATE_PER_MINUTE` | Java | 默认 10 |
| `DEMO_SEED_ENABLED` | Java | 是否写入演示账号 |
| `DOCS_PUBLIC_ACCESS` | Java | Knife4j 是否免登录 |
| `CORS_ALLOWED_ORIGINS` | Java | 默认含 `5177` |
| `UPLOAD_ROOT_DIR` | Java | 上传根目录 |
| `APP_ENV` | Agent | `development` / `production` |
| `AGENT_MOCK_LLM` / `EMBEDDING_MOCK` | Agent | 见 8.5 |
| `DEEPSEEK_API_KEY` / `LLM_MODEL` | Agent | 真实规划与写稿 |
| `TAVILY_API_KEY` | Agent | 联网检索 |
| `EMBEDDING_*` | Agent | 真实嵌入 |
| `ALLOW_SYNTHETIC_DEMO` | Agent | 是否允许演示用合成证据（生产应关闭） |
| `CHECKPOINT_BACKEND` | Agent | 默认 `postgres` |
| `SANDBOX_*` / `ARTIFACT_*` | Agent | 沙箱镜像、超时、资源与产物上限 |
| `EXECUTION_LEASE_WAIT_SECONDS` | Agent | 执行租约等待 |

模板见仓库根 `.env.example` 与 `deploy/ubuntu/agent.env.example`。真实值只放本机 `.env`、Windows 用户环境变量或 `/etc/insighthub/agent.env`，不要提交 Git，也不要贴进日志或工单。

---

## 13. 本机启动顺序

推荐：**Ubuntu Agent 与其依赖 → Windows MySQL → Java → 前端**。Java 启动会检查 Agent 地址和内部令牌。

### 13.1 Ubuntu Agent（日常）

```bash
sudo systemctl start docker
sudo -u insighthub docker compose \
  -f /opt/insighthub/agent/deploy/ubuntu/docker-compose.agent.yml \
  --env-file /etc/insighthub/agent.env up -d --wait
sudo systemctl start insighthub-agent
curl -fsS http://127.0.0.1:8000/health
```

从 Windows：

```powershell
Test-NetConnection <ubuntu-ip> -Port 8000
Invoke-RestMethod http://<ubuntu-ip>:8000/health
```

完整发布、`uv sync --frozen --package insighthub-agent-service`、重建 Sandbox、UFW 约束见 `deploy/ubuntu/README.md` 与 `docs/insighthub-current-system-guide.md` 第 10 节。

### 13.2 Windows Java

```powershell
cd C:\Users\Dell\Project\Second\Project\Demo
# 确保 .env 或用户环境变量中已有 JWT_SECRET、AGENT_BASE_URL、AGENT_INTERNAL_TOKEN
cd backend-java
mvn -DskipTests spring-boot:run
```

API 文档：`http://127.0.0.1:8080/doc.html`。

### 13.3 前端

```powershell
cd C:\Users\Dell\Project\Second\Project\Demo\insightHub-frontend
npm run dev
```

浏览器打开 `http://127.0.0.1:5177`。

无真实模型密钥时，可在 Agent 侧使用 `AGENT_MOCK_LLM=true` 与 `EMBEDDING_MOCK=true` 做链路验收，不能当作研究结论。

---

## 14. 端到端任务路径（对照代码）

```text
用户提交问题
  → ResearchTaskServiceImpl.createAsync
  → 落 research_task，状态 PLANNING
  → TaskExecutionServiceImpl 拉 NDJSON
  → PLAN_CREATED
  → recordPlannerResult 插入 PENDING 修订
  → 前端确认（expectedRevision）
  → Outbox EXECUTE + Agent /plan/approve
  → 研究 / 核验 / Critic /（可选 Sandbox）/ Writer
  → TASK_RESULT
  → TaskResultService 写不可变 report + citation + 质量
```

修订路径：`revise` → `SUPERSEDED` + 新 PLAN 流 → 新 PENDING。  
重试路径：`retry` → `planRevision = max+1` + 新 `runId` → 再走 PLAN；落库时再按库内最大号 +1 插入新 PENDING。  
暂停/恢复：Redis 控制字 + Checkpoint resume，不新开修订号。

---

## 15. 常见问题

| 现象 | 常见原因 | 处理方向 |
| --- | --- | --- |
| Java 启动失败，提示 Agent 未配置 | `AGENT_BASE_URL` 或 `AGENT_INTERNAL_TOKEN` 为空 | 写入环境变量后**重启** IDE |
| `AGENT_STREAM_FAILED` / 连接超时 | Java 仍指向旧 VM IP，或 Agent 未启动 | 核对 IP、`/health`、防火墙 8000 |
| `SEARCH_UNAVAILABLE` / web search failed | Tavily 瞬时网络或 Key 无效 | 生产模式不会合成证据；可稍后重试 |
| `Duplicate entry '...-1' uk_task_plan_revision` | 旧代码重试仍插入修订 1 | 已修复：递增修订号 + 幂等落库；重启 Java 后再重试 |
| 「计划已更新，请确认最新版本」 | 确认的是已 APPROVED 的旧修订，或指针已指向更新 PENDING | 刷新后确认**当前 PENDING** |
| 步骤全部「依赖失败，已跳过」 | 靠前的检索/研究步骤已失败 | 先看第一条失败工具事件 |
| SSE 停在中途但库已是 FAILED | 流断开后前端未刷新终态 | 刷新详情或重连 SSE |
| 知识库一直 PARSING | Python 入库未成功或 Embedding 未配置 | 看 Agent 日志；开发可用 `EMBEDDING_MOCK` |
| Sandbox 失败 | 镜像不存在或 Docker 不可用 | 构建 `insighthub-analysis-sandbox:1.0.0` |

从 Windows 同步到 Ubuntu 的 `.sh` 若带 CRLF，systemd/env 脚本可能读失败。在 Linux 上应去掉 `\r` 后再执行。

---

## 16. 相关文档

| 文档 | 内容 |
| --- | --- |
| [protocol.md](protocol.md) | Java ↔ Python 请求/事件字段 |
| [database-schema.md](database-schema.md) | 表字段说明（设计稿整理；以 Flyway 为准） |
| [environment-setup.md](environment-setup.md) | 早期本机基础设施记录 |
| [insighthub-current-system-guide.md](insighthub-current-system-guide.md) | 2026-08-23 部署手册（Ubuntu 命令更细） |
| [../deploy/ubuntu/README.md](../deploy/ubuntu/README.md) | Ubuntu Agent 发布约束 |
| [../backend-java/README.md](../backend-java/README.md) | Java 模块说明 |
| [../agent-service-python/README.md](../agent-service-python/README.md) | Agent 模块说明 |

协议或表结构变更时，先改 Flyway / `AgentTaskRequestFactory` / 前端类型，再同步更新本文与 `protocol.md`。
