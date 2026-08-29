# InsightHub Agent Service（Python）

FastAPI + LangGraph：**Supervisor + Handoff** 多自治 Agent（Planner / 研究员 / Critic / Writer），审批与证据核验是系统闸门。

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

仓库根目录 `.env` 必须配置与 Java 相同的 `AGENT_INTERNAL_TOKEN`。真实模型模式还需配置
`DEEPSEEK_API_KEY`；也可设置 `AGENT_MOCK_LLM=true` 跑演示。

### Checkpoint / 多进程

生产默认使用 PostgreSQL Checkpoint（`CHECKPOINT_BACKEND=postgres`，`thread_id=taskId`），并通过
Redis 执行租约避免多 worker 同时驱动同一任务。PostgreSQL 和 Redis 不可用时拒绝启动任务。
单元测试显式使用 `CHECKPOINT_BACKEND=memory`，不连接外部服务。

可按部署容量启动多个 worker，例如：

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 2
```

## 测试

```powershell
$env:AGENT_MOCK_LLM="true"
$env:AGENT_MOCK_STEP_DELAY_MS="0"
uv run pytest -q
```
