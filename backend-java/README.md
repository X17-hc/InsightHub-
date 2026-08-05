# InsightHub Java 平台服务

Spring Boot 3.3 / JDK 21。第 2 周能力：JWT、工作空间 RBAC、Agent 配置、任务状态机、Knife4j。

## IntelliJ：`java.util` 报红

根仓库若被识别成 Python 项目，Project SDK 可能是 `uv (InsightHub)`，会导致连 JDK 基础包都无法解析。

处理：

1. **File → Project Structure → Project SDK** 选 **21**（`C:\Program Files\Java\jdk-21.0.11`）
2. **Maven** 工具窗对 `backend-java/pom.xml` 点 **Reload**
3. 若仍异常：**File → Invalidate Caches → Invalidate and Restart**

本仓库 `.idea/misc.xml` 已将项目 SDK 设为 Java 21；`backend-java` 为独立 Java Maven 模块。

## 启动

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\backend-java
# 需 MySQL（insighthub）与 Python Agent :8000
mvn -DskipTests spring-boot:run
```

- 健康检查：http://127.0.0.1:8080/api/v1/health
- Knife4j：http://127.0.0.1:8080/doc.html

## 演示数据（启动自动 seed）

| 用户 | 密码 | 工作空间 |
| --- | --- | --- |
| `demo` | `demo123456` | `workspace-demo`（OWNER） |
| `demob` | `demo123456` | `workspace-demo-b`（OWNER） |

每个空间预置 PLANNER / SUPERVISOR / WEB_RESEARCHER（enabled）。

## 主要 API

- Auth：`/api/v1/auth/**`
- Workspace：`/api/v1/workspaces/**`
- Agent：`/api/v1/workspaces/{workspaceId}/agents/**`
- Task：`/api/v1/workspaces/{workspaceId}/research/tasks/**`

配置见 `src/main/resources/application.yml`：

| 配置 | 说明 |
| --- | --- |
| `insighthub.jwt.secret` / `JWT_SECRET` | ≥32 字节，短密钥启动失败 |
| `insighthub.demo.seed-enabled` / `DEMO_SEED_ENABLED` | 演示账号 seed，生产设 `false` |
| `insighthub.docs.public-access` / `DOCS_PUBLIC_ACCESS` | Knife4j 是否匿名可访问 |
| `insighthub.agent.base-url` | Python Agent 地址 |
| `insighthub.task.*` | 超时 / 创建限流 / SSE 心跳 |
| Redis | `spring.data.redis.*`（并发槽、限流、事件 Pub/Sub） |

### 第 3 周任务 API

- `POST .../research/tasks` → **202** 异步
- `POST .../research/tasks/sync` → 同步（兼容旧演示）
- `GET .../research/tasks/{id}/events` → SSE（`Last-Event-ID` / `fromEventNo`；仅此路径可用 `?access_token=`）
- `POST .../pause|resume|cancel|retry`
