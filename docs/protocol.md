# InsightHub 服务通信协议

> Java 平台服务 ↔ Python Agent 服务  
> 第 1 周：同步 JSON（非 SSE）。第 2 周：JWT + 工作空间隔离。流式推送见第 3 周。

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

## 4. Java 对外 API（第 2 周）

### 4.1 鉴权

| 接口 | 说明 |
| --- | --- |
| `POST /api/v1/auth/register` | 注册 |
| `POST /api/v1/auth/login` | 返回 `accessToken` + `refreshToken` |
| `POST /api/v1/auth/refresh` | 刷新令牌 |
| `GET /api/v1/auth/me` | 当前用户（需 Bearer） |

业务接口请求头：

```http
Authorization: Bearer <accessToken>
```

工作空间通过 URL 路径 `{workspaceId}` 指定；服务端校验当前用户为该空间成员，非成员返回 **403**。

### 4.2 工作空间与 Agent

| 接口 | 权限 |
| --- | --- |
| `POST/GET /api/v1/workspaces` | 登录用户 |
| `GET /api/v1/workspaces/{id}` | 成员 |
| `GET/POST /api/v1/workspaces/{id}/members` | 读：成员；写：OWNER/ADMIN |
| `DELETE /api/v1/workspaces/{id}/members/{userId}` | OWNER/ADMIN |
| `GET/POST /api/v1/workspaces/{workspaceId}/agents` | 读：成员；写：ADMIN+ |
| `PUT .../agents/{agentId}`、`.../enable`、`.../disable` | ADMIN+ |

### 4.3 研究任务（强制工作空间隔离）

```http
POST /api/v1/workspaces/{workspaceId}/research/tasks
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{ "query": "比较 Spring AI 和 LangChain4j 的多 Agent 能力" }
```

```http
GET /api/v1/workspaces/{workspaceId}/research/tasks
GET /api/v1/workspaces/{workspaceId}/research/tasks/{taskId}
```

响应与 Agent 成功响应字段对齐，并额外可含 Java 侧 `traceId`。  
任务状态机（同步路径）：`CREATED → PLANNING → RUNNING → GENERATING → COMPLETED`（失败 → `FAILED`）。

API 文档：`http://localhost:8080/doc.html`（Knife4j，Authorize 填 Bearer Token）。

---

## 5. 健康检查

- Python：`GET /health` → `{ "status": "ok" }`
- Java：`GET /api/v1/health`（无需登录）
