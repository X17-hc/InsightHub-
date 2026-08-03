# InsightHub

多智能体研究与知识生产平台（Java + Python）。

- GitHub：https://github.com/X17-hc/InsightHub-.git
- 本地路径：`C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub`

## 第 1 周验收

用户提交研究问题后，经 **Planner → Supervisor → Researcher** 协作，返回 Markdown 报告。

## 快速启动

### 1. 环境

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub
Copy-Item .env.example .env   # 若尚无 .env
# 编辑 .env：可填 DEEPSEEK_API_KEY；没有 Key 时保持 AGENT_MOCK_LLM=true
.\scripts\start-mysql.ps1
.\scripts\check-env.ps1
.\scripts\apply-schema.ps1   # 首次或表结构变更后
```

### 2. Python Agent（端口 8000）

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\agent-service-python
uv sync --extra dev
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### 3. Java 平台（端口 8080）

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\backend-java
# 需本机 Maven + JDK 21
mvn -DskipTests spring-boot:run
```

### 4. 演示验收

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub
.\scripts\run-week1-demo.ps1
```

期望：`status=COMPLETED`，`reportMarkdown` 以 `#` 开头，事件含 `PLAN_CREATED` / `NODE_COMPLETED` / `TASK_COMPLETED`。

## 文档

| 文档 | 说明 |
| --- | --- |
| [docs/protocol.md](docs/protocol.md) | Java ↔ Python 请求/事件/错误协议 |
| [docs/database-schema.md](docs/database-schema.md) | MySQL / PGVector 表结构 |
| [docs/environment-setup.md](docs/environment-setup.md) | 本机基础设施 |

## 仓库结构

```text
InsightHub/
├── agent-service-python/   # FastAPI + LangGraph
├── backend-java/           # Spring Boot 调用层
├── deploy/                 # Docker Compose 与 DDL
├── docs/
└── scripts/
```
