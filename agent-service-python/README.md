# InsightHub Agent Service（Python）

第 1 周：FastAPI + LangGraph 最小三 Agent 链路（Planner / Supervisor / Researcher）。

## IntelliJ：import 报红

请使用本目录解释器（不要用仓库根 `.venv`）：

1. **File → Project Structure → Modules → agent-service-python → Python**  
   Interpreter：`agent-service-python/.venv`（SDK 名可为 `uv (InsightHub)`）
2. 或右下角 Python Interpreter → 选  
   `...\InsightHub\agent-service-python\.venv\Scripts\python.exe`
3. 仍报红：**File → Invalidate Caches → Invalidate and Restart**

依赖安装：在本目录执行 `uv sync`。

## 启动

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\agent-service-python
uv sync --extra dev
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

仓库根目录 `.env` 需配置 `DEEPSEEK_API_KEY`（或设置 `AGENT_MOCK_LLM=true` 跑演示）。

### Checkpoint / 多进程

流式 pause/resume 依赖进程内 `MemorySaver`（`thread_id=taskId`）。请用**单 worker** 启动，例如：

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
```

进程重启后无法跨进程 resume；Java 侧事件仍可从 MySQL 经 SSE 回放。

## 测试

```powershell
$env:AGENT_MOCK_LLM="true"
$env:AGENT_MOCK_STEP_DELAY_MS="0"
uv run pytest -q
```
