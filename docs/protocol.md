# InsightHub 服务通信协议

> Java 平台服务 ↔ Python Agent 服务  
> 第 1 周：同步 JSON。第 2 周：JWT + 工作空间隔离。第 3 周：NDJSON 流 + SSE 断线续传 + 暂停/取消/重试。第 4 周：知识库入库 + PGVector 混合检索 + 引用可追溯。

---

## 1. 创建 Agent 任务

### 请求

```http
POST /internal/v1/agent/tasks
Content-Type: application/json
X-Trace-Id: <traceId>
X-Idempotency-Key: <taskId>-attempt-<n>
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| taskId | string | 是 | 全局唯一任务 ID |
| workspaceId | string | 是 | 工作空间 ID |
| userId | string | 是 | 发起用户 ID |
| query | string | 是 | 研究主题 |
| knowledgeBaseIds | string[] | 否 | 绑定的知识库 ID 列表（第 4 周生效；Agent 检索 PGVector） |
| config.maxSteps | int | 否 | 默认 20 |
| config.maxParallelism | int | 否 | 默认 3 |
| config.requirePlanApproval | bool | 否 | 第 1 周固定按 false 处理 |
| config.enableWebSearch | bool | 否 | 默认 true |

### 成功响应

```json
{
  "taskId": "task-xxx",
  "runId": "run-xxx",
  "status": "COMPLETED",
  "reportMarkdown": "# 标题\n...",
  "events": [],
  "error": null
}
```

| status | 说明 |
| --- | --- |
| COMPLETED | 成功生成报告 |
| FAILED | 图执行失败（见 error） |

### 错误响应（HTTP 4xx/5xx）

```json
{
  "code": "AGENT_EXECUTION_FAILED",
  "message": "可读错误信息",
  "traceId": "trace-xxx",
  "details": {}
}
```

常用 `code`：`VALIDATION_ERROR`、`IDEMPOTENCY_CONFLICT`、`AGENT_EXECUTION_FAILED`、`MAX_STEPS_EXCEEDED`、`UPSTREAM_TIMEOUT`。

---

## 2. 事件格式

```json
{
  "schemaVersion": "1.0",
  "eventId": 1,
  "taskId": "task-xxx",
  "runId": "run-xxx",
  "node": "create_plan",
  "type": "NODE_COMPLETED",
  "timestamp": "2026-08-03T08:00:00Z",
  "data": {}
}
```

`REPORT_DELTA` 的 `data` 固定包含 `delta`、`sequence` 和 `done`，客户端必须按
`eventId` 去重，并按 `sequence` 追加报告片段。

### 事件类型（全集声明）

| type | 第 1 周是否产出 |
| --- | --- |
| TASK_STARTED | 是 |
| PLAN_CREATED | 是 |
| APPROVAL_REQUIRED | 否（预留） |
| NODE_STARTED | 是 |
| TOOL_CALLED | 可选 |
| TOOL_COMPLETED | 可选 |
| NODE_COMPLETED | 是 |
| NODE_RETRYING | 否（预留） |
| REPORT_DELTA | 是（第 3 周流式） |
| TASK_PAUSED | 否（预留） |
| TASK_COMPLETED | 是 |
| TASK_FAILED | 是 |

`eventId` 在单次任务内从 1 单调递增，供后续 SSE 断线续传。

---

## 3. 幂等

- 键：请求头 `X-Idempotency-Key`
- 语义：相同键在 Python 进程内返回**首次**成功/失败结果
- 第 1 周：内存 dict；第 3 周可迁 Redis

---

## 4. 内部流式（第 3 周，Python → Java）

```http
POST /internal/v1/agent/tasks/stream
Content-Type: application/json
Accept: application/x-ndjson
```

响应为 **NDJSON**：一行一个事件 JSON；最后一行为：

```json
{ "type": "TASK_RESULT", "taskId": "...", "runId": "...", "status": "COMPLETED|FAILED|PAUSED|CANCELLED", "reportMarkdown": "...", "error": null }
```

`config.nextEventId`（可选）：Java retry 时传入 `MAX(event_no)+1`，保证同 `taskId` 事件号继续递增。

恢复：

```http
POST /internal/v1/agent/tasks/{taskId}/resume
```

控制字 Redis：`ih:task:{taskId}:control` = `RUNNING|PAUSED|CANCELLED`。

**Checkpoint 约束（第 3 周）**：Python 使用进程内 `MemorySaver`；**单进程**有效。进程重启或多 uvicorn worker 后 `/resume` 可能 `NO_CHECKPOINT`，此时以 MySQL 事件回放为准，需全量 `/stream` 重跑（retry）。

---

## 5. Java 对外 API

### 5.0 统一响应信封（BaseResponse）

除 **SSE**（`.../events`）外，平台 API 均返回：

```json
{ "code": 0, "data": { }, "message": "ok" }
```

| 字段 | 说明 |
| --- | --- |
| `code` | `0` 成功；失败为业务码（如 `40100` 未登录、`40300` 禁止访问、`40400` 不存在、`42900` 限流） |
| `data` | 业务载荷；失败多为 `null` |
| `message` | 说明；失败时可含原业务细码前缀 |

失败时 **HTTP 通常仍为 200**（与全局异常处理一致）；客户端应优先判断 `code`，不要只看 HTTP 状态。  
异步创建/重试仍可返回 **HTTP 202**，响应体仍是上述信封（`data` 内为 `taskId` 等）。

### 5.1 鉴权

| 接口 | 说明 |
| --- | --- |
| `POST /api/v1/auth/register` | 注册 |
| `POST /api/v1/auth/login` | `data` 含 `accessToken` + `refreshToken` |
| `POST /api/v1/auth/refresh` | 刷新令牌 |
| `GET /api/v1/auth/me` | 当前用户（需 Bearer） |

```http
Authorization: Bearer <accessToken>
```

SSE **仅** `.../research/tasks/{taskId}/events` 可用查询参数：`?access_token=<accessToken>`（EventSource 不便带 Header；其它 API 必须用 Bearer）。

### 5.2 研究任务（第 3 周）

Base：`/api/v1/workspaces/{workspaceId}/research/tasks`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/` | **异步 202**，body 可含 `knowledgeBaseIds`；`data`=`{taskId,status,traceId}` |
| POST | `/sync` | 同步（兼容 week1/2），可含 `knowledgeBaseIds`；结果在 `data` |
| GET | `/{taskId}/events` | SSE（**不套信封**）；`Last-Event-ID` 或 `?fromEventNo=` 续传 |
| GET | `/{taskId}/citations` | 引用列表（`citationNo` / `sourceType` / `documentId` / `chunkId`） |
| POST | `/{taskId}/pause` | RUNNING→PAUSED |
| POST | `/{taskId}/resume` | PAUSED→RUNNING |
| POST | `/{taskId}/cancel` | 取消（含 GENERATING） |
| POST | `/{taskId}/retry` | FAILED→RUNNING（202） |

创建任务 body 示例：

```json
{
  "query": "对比 Spring AI 与 LangChain4j",
  "knowledgeBaseIds": ["kb-xxx"]
}
```

`TASK_RESULT`（Python→Java）除 `reportMarkdown` 外可含 `citations[]`，Java 落库 `citation` 表。

SSE 示例：

```http
GET /api/v1/workspaces/{workspaceId}/research/tasks/{taskId}/events?access_token=...
Last-Event-ID: 3
Accept: text/event-stream
```

事件投递 **at-least-once**；客户端按 `eventId` 去重。

### 5.3 工作空间与 Agent

同第 2 周：`/api/v1/workspaces/**`、`/agents/**`；非成员返回信封 `code=40300`（HTTP 多为 200）。

### 5.4 知识库（第 4 周）

Base：`/api/v1/workspaces/{workspaceId}/knowledge-bases`（需成员权限）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/` | 创建 KB：`{name, description?}` |
| GET | `/` | 列出本空间 KB |
| GET | `/{kbId}` | 详情 |
| DELETE | `/{kbId}` | 禁用（`DISABLED`）并清理 PG 片段 |
| POST | `/{kbId}/documents` | `multipart` 上传 txt/md/pdf（≤5MB） |
| GET | `/{kbId}/documents` | 文档列表 / `parseStatus` |
| GET | `/{kbId}/documents/{docId}` | 文档详情 |
| POST | `/{kbId}/documents/{docId}/reindex` | 失败重试入库 |

`parseStatus`：`PENDING → PARSING → INDEXED|FAILED`。

### 5.5 内部知识库 API（Java → Python）

| 接口 | 说明 |
| --- | --- |
| `POST /internal/v1/knowledge/documents/ingest` | `{workspaceId,knowledgeBaseId,documentId,filePath,...}` → 分块+向量写入 PG |
| `POST /internal/v1/knowledge/retrieve` | 混合检索（向量 + 关键词 + RRF） |
| `POST /internal/v1/knowledge/chunks/delete-by-kb` | 删除某 KB 全部片段 |

Embedding：`EMBEDDING_MOCK=true` 或 `AGENT_MOCK_LLM=true` 时用确定性 1536 维伪向量。

API 文档：`http://localhost:8080/doc.html`。

---

## 6. 健康检查

- Python：`GET /health` → `{ "status": "ok" }`（Agent 侧，无信封）
- Java：`GET /api/v1/health` → `{ "code": 0, "data": { "status": "ok" }, "message": "ok" }`（无需登录）

