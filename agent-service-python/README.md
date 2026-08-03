# InsightHub Agent Service（Python）

第 1 周：FastAPI + LangGraph 最小三 Agent 链路（Planner / Supervisor / Researcher）。

## 启动

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\agent-service-python
uv sync
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

仓库根目录 `.env` 需配置 `DEEPSEEK_API_KEY`（或设置 `AGENT_MOCK_LLM=true` 跑演示）。

## 测试

```powershell
$env:AGENT_MOCK_LLM="true"
uv run pytest -q
```
