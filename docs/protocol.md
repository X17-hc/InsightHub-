# InsightHub 服务通信协议

> Java 平台服务 ↔ Python Agent 服务  
> 第 1 周：同步 JSON。第 2 周：JWT + 工作空间隔离。第 3 周：NDJSON 流 + SSE 断线续传 + 暂停/取消/重试。

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
| knowledgeBaseIds | string[] | 否 | 知识库 ID 列表（第 1 周可空） |
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
  "eventId": 1,
  "taskId": "task-xxx",
  "runId": "run-xxx",
  "node": "create_plan",
  "type": "NODE_COMPLETED",
  "timestamp": "2026-08-03T08:00:00Z",
  "data": {}
}
```

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
| REPORT_TOKEN | 否（第 3 周流式） |
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

### 5.1 鉴权

| 接口 | 说明 |
| --- | --- |
| `POST /api/v1/auth/register` | 注册 |
| `POST /api/v1/auth/login` | 返回 `accessToken` + `refreshToken` |
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
| POST | `/` | **异步 202** `{taskId,status,traceId}` |
| POST | `/sync` | 同步 200（兼容 week1/2） |
| GET | `/{taskId}/events` | SSE；`Last-Event-ID` 或 `?fromEventNo=` 续传 |
| POST | `/{taskId}/pause` | RUNNING→PAUSED |
| POST | `/{taskId}/resume` | PAUSED→RUNNING |
| POST | `/{taskId}/cancel` | 取消（含 GENERATING） |
| POST | `/{taskId}/retry` | FAILED→RUNNING（202） |

SSE 示例：

```http
GET /api/v1/workspaces/{workspaceId}/research/tasks/{taskId}/events?access_token=...
Last-Event-ID: 3
Accept: text/event-stream
```

事件投递 **at-least-once**；客户端按 `eventId` 去重。

### 5.3 工作空间与 Agent

同第 2 周：`/api/v1/workspaces/**`、`/agents/**`，非成员 **403**。

API 文档：`http://localhost:8080/doc.html`。

---

## 6. 健康检查

- Python：`GET /health` → `{ "status": "ok" }`
- Java：`GET /api/v1/health`（无需登录）

