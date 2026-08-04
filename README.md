# InsightHub

多智能体研究与知识生产平台（Java + Python）。

- GitHub：https://github.com/X17-hc/InsightHub-.git
- 本地路径：`C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub`


- Planner → Supervisor → Researcher 产出 Markdown 报告
- JWT 鉴权、工作空间 RBAC、Agent 配置、任务状态机、Knife4j；**跨工作空间数据隔离**

## 快速启动

### 1. 环境

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub
Copy-Item .env.example .env   # 若尚无 .env
.\scripts\start-mysql.ps1
.\scripts\check-env.ps1
.\scripts\apply-schema.ps1   # 首次或表结构变更后
```

### 2. Python Agent（端口 8000）

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\agent-service-python
uv sync --extra dev
$env:AGENT_MOCK_LLM="true"   # 无 DeepSeek Key 时
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### 3. Java 平台（端口 8080）

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\backend-java
mvn -DskipTests spring-boot:run
```

- API 文档：http://127.0.0.1:8080/doc.html
- 演示账号：`demo` / `demob`，密码均为 `demo123456`
- 演示工作空间：`workspace-demo`、`workspace-demo-b`

### 4. 验收

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub
.\scripts\run-week1-demo.ps1          # 登录后创建研究任务
.\scripts\run-week2-isolation-demo.ps1 # 跨空间 403 隔离
```

## 文档

| 文档 | 说明 |
| --- | --- |
| [docs/protocol.md](docs/protocol.md) | 鉴权、工作空间 API、Java↔Python 协议 |
| [docs/database-schema.md](docs/database-schema.md) | MySQL / PGVector 表结构 |
| [docs/environment-setup.md](docs/environment-setup.md) | 本机基础设施 |

## 仓库结构

```text
InsightHub/
├── agent-service-python/   # FastAPI + LangGraph
├── backend-java/           # Spring Boot 平台服务
├── deploy/
├── docs/
└── scripts/
```
