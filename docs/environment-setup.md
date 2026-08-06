# InsightHub 本地环境配置说明

> 更新日期：2026-08-03  
> 适用机器：当前 Windows 开发机（`C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub`）  
> 目标：为 InsightHub 准备 MySQL、Redis、PostgreSQL(+PGVector) 基础设施

---

## 1. 配置结果摘要

本机已按 **原生安装（native）** 模式完成基础设施配置，连通性验证通过。

| 组件 | 状态 | 地址 | 说明 |
| --- | --- | --- | --- |
| MySQL 8.4.9 | 已就绪 | `127.0.0.1:3306` | 业务库 `insighthub` |
| Redis 3.0.504 | 已就绪 | `127.0.0.1:6379` | Windows 服务，开机自启 |
| PostgreSQL 16.14 + pgvector 0.8.6 | 已就绪 | `127.0.0.1:5432` | 向量库 `insighthub_vector` |
| Docker Desktop | 未安装 | - | 可选；仓库已提供 `deploy/docker-compose.yml` |
| WSL | 未安装 | - | 安装 Docker Desktop 前需先装 WSL2 |

一键检查：

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub
.\scripts\check-env.ps1
```

期望输出包含：`结果: 全部通过`。

---

## 2. 账号与连接信息（仅限本地开发）

**生产环境务必修改密码，且不要把 `.env` 提交到 Git。**

### 2.1 MySQL

| 项 | 值 |
| --- | --- |
| Host / Port | `127.0.0.1:3306` |
| Database | `insighthub` |
| App 用户 | `insighthub` / `123456` |
| Root 用户 | `root` / `123456` |
| JDBC | `jdbc:mysql://127.0.0.1:3306/insighthub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai` |

验证：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" `
  -h 127.0.0.1 -P 3306 -u insighthub -p123456 `
  --protocol=tcp -e "SHOW DATABASES;"
```

### 2.2 Redis

| 项 | 值 |
| --- | --- |
| Host / Port | `127.0.0.1:6379` |
| Password | 无（本地默认） |
| 安装路径 | `C:\Program Files\Redis` |
| Windows 服务名 | `Redis`（Automatic） |

验证：

```powershell
redis-cli ping
# 期望：PONG
```

> 说明：当前为 Microsoft 归档版 Redis 3.0.504，满足本地缓存 / 锁 / PubSub 开发。若后续需要 Streams 等新特性，可改用 Docker Redis 7 或 Memurai（需管理员权限安装）。

### 2.3 PostgreSQL + PGVector

| 项 | 值 |
| --- | --- |
| Host / Port | `127.0.0.1:5432` |
| Database | `insighthub_vector` |
| App 用户 | `insighthub` / `123456` |
| 超级用户（初始化用） | `postgres` / `123456` |
| 扩展 | `vector` **0.8.6**（已 `CREATE EXTENSION`） |
| URL | `postgresql+psycopg://insighthub:123456@127.0.0.1:5432/insighthub_vector` |

验证：

```powershell
$env:PGPASSWORD = "123456"
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" `
  -U insighthub -h 127.0.0.1 -p 5432 -d insighthub_vector `
  -c "SELECT extversion FROM pg_extension WHERE extname='vector';" `
  -c "SELECT '[1,2,3]'::vector <-> '[4,5,6]'::vector AS l2_distance;"
```

期望：`extversion = 0.8.6`，`l2_distance ≈ 5.196152422706632`。

---

## 3. 本机实际做了什么

### 3.1 Redis

- 通过 `winget install Redis.Redis` 安装
- Windows 服务 `Redis` 已运行，端口 `6379`
- `redis-cli ping` 返回 `PONG`

### 3.2 MySQL

- 通过 `winget install Oracle.MySQL` 安装了 MySQL Server 8.4 程序文件
- **未能注册 Windows 系统服务**（当前 shell 无提权），因此采用用户目录数据实例：
  - 配置：`deploy/runtime/mysql/my.ini`
  - 数据：`deploy/runtime/mysql/data/`
  - 日志：`deploy/runtime/mysql/logs/`
- 已创建库 `insighthub` 与用户 `insighthub`
- 启停脚本：
  - 启动：`.\scripts\start-mysql.ps1`
  - 停止：`.\scripts\stop-mysql.ps1`

> 重启电脑后，Redis / PostgreSQL 会随服务自启；**MySQL 需要重新执行** `.\scripts\start-mysql.ps1`。

### 3.3 PostgreSQL / PGVector

- 本机原有 PostgreSQL 16 服务（`postgresql-x64-16`）
- `pgvector` 扩展文件此前已安装到：
  - `C:\Program Files\PostgreSQL\16\lib\vector.dll`
  - `C:\Program Files\PostgreSQL\16\share\extension\vector*`
- 本次新建：
  - 角色 `insighthub`
  - 数据库 `insighthub_vector`
  - 在该库执行 `CREATE EXTENSION vector;`
- 冒烟测试通过（向量 L2 距离计算正常）

### 3.4 项目内新增文件

```text
TEST/
├── .env                          # 本机真实配置（已 gitignore）
├── .env.example                  # 可提交的模板
├── docs/
│   └── environment-setup.md      # 本文档
├── deploy/
│   ├── docker-compose.yml        # 可选 Docker 方案
│   ├── mysql/init/01_init.sql
│   ├── postgres/init/01_init.sql
│   └── runtime/mysql/            # 本机 MySQL 数据（gitignore）
└── scripts/
    ├── check-env.ps1
    ├── start-mysql.ps1
    ├── stop-mysql.ps1
    └── init-postgres.ps1
```

---

## 4. 日常使用

### 4.1 每天开始开发前

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub

# 1) 确保 MySQL 起来（Redis/Postgres 一般已是服务）
.\scripts\start-mysql.ps1

# 2) 连通性检查
.\scripts\check-env.ps1
```

### 4.2 重新初始化 PostgreSQL（换机器或库被删时）

```powershell
.\scripts\init-postgres.ps1
```

脚本会读取 `.env` 中的 `POSTGRES_SUPERUSER_PASSWORD`；若为空则交互输入。

### 4.3 应用侧读取配置

Java / Python 服务启动时读取项目根目录 `.env`（或 IDE Run Configuration 注入同名环境变量）。关键变量：

- `MYSQL_JDBC_URL` / `MYSQL_USER` / `MYSQL_PASSWORD`
- `REDIS_HOST` / `REDIS_PORT`
- `POSTGRES_URL` 或 `POSTGRES_HOST` + `POSTGRES_PORT` + `POSTGRES_DB` + `POSTGRES_USER` + `POSTGRES_PASSWORD`

---

## 5. 可选：Docker Compose 方案

当前机器 **未安装 Docker Desktop / WSL**，因此默认使用原生服务。若以后安装了 Docker，可用仓库内 Compose 一键拉起隔离环境：

```powershell
# 需先安装 WSL2 + Docker Desktop，并确保 docker 命令可用
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps
```

Compose 端口约定：

| 服务 | 容器端口 | 宿主机默认端口 |
| --- | --- | --- |
| MySQL | 3306 | 3306 |
| Redis | 6379 | 6379 |
| PGVector | 5432 | **5433**（避免和本机 Postgres 冲突） |

若改用 Docker PGVector，请同步修改 `.env`：

```env
INSIGHTHUB_INFRA_MODE=docker
POSTGRES_PORT=5433
POSTGRES_URL=postgresql+psycopg://insighthub:123456@127.0.0.1:5433/insighthub_vector
```

并注意：不要同时让原生 MySQL 与 Compose MySQL 占用 3306。

---

## 6. 已知限制与后续建议

1. **MySQL 非 Windows 服务**  
   重启后需手动 `start-mysql.ps1`。若要以系统服务方式常驻，请用「管理员 PowerShell」执行 MySQL 官方配置 / `mysqld --install`。

2. **Redis 版本偏旧（3.0）**  
   对课程/简历项目的缓存与分布式锁够用；若要用 Redis Stream / 新命令，改 Docker Redis 7。

3. **密钥仅适合本机**  
   `.env` 中的密码为开发默认值；公开仓库前确认 `.env` 已被 ignore，且勿把超级用户密码写进 README。

4. **尚未安装的应用运行时**  
   本阶段只配基础设施。后续还需要：
   - Java 21 + Spring Boot 工程依赖
   - Python 3.12 虚拟环境（`uv` / `venv`）与 LangGraph 依赖
   - Node.js（已检测到 v24）用于 Vue3 前端
   - （可选）Nginx、Python 沙箱容器

5. **Docker 路线前置**  
   ```powershell
   wsl --install
   winget install Docker.DockerDesktop
   ```
   安装后重启，再使用 `deploy/docker-compose.yml`。

---

## 7. 故障排查

| 现象 | 处理 |
| --- | --- |
| `check-env` MySQL FAIL | 执行 `.\scripts\start-mysql.ps1`；查看 `deploy/runtime/mysql/logs/mysqld.err` |
| 3306 端口被占用 | `netstat -ano \| findstr :3306`，结束冲突进程或改 `my.ini` 端口 |
| Redis 非 PONG | `Start-Service Redis`；或重开终端刷新 PATH |
| Postgres 密码错误 | 更新 `.env` 的 `POSTGRES_SUPERUSER_PASSWORD`，再跑 `init-postgres.ps1` |
| `vector` 扩展不存在 | 确认 `vector.dll` 在 Postgres `lib` 目录；在目标库执行 `CREATE EXTENSION vector;` |
| pgvector 在 A 库有、B 库没有 | 扩展是**库级**的，每个库都要执行一次 `CREATE EXTENSION` |

---

## 8. 验收清单

- [x] MySQL 可连接，存在库 `insighthub`
- [x] Redis `PING` -> `PONG`
- [x] PostgreSQL 存在库 `insighthub_vector`
- [x] `vector` 扩展版本 0.8.6，向量距离查询成功
- [x] 项目根目录有 `.env` / `.env.example`
- [x] 提供 `scripts/check-env.ps1` 与 MySQL 启停脚本
- [x] 提供可选 `deploy/docker-compose.yml`
- [x] 第 1 周：Python Agent（8000）+ Java 平台（8080）最小链路可演示
- [x] 业务表 DDL 已落地（见 `docs/database-schema.md`）

---

## 9. 第 1 周服务启动

工作目录：`C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub`

1. `.\scripts\start-mysql.ps1` 与 `.\scripts\check-env.ps1`
2. Python：`cd agent-service-python; uv run uvicorn app.main:app --host 127.0.0.1 --port 8000`
3. Java：`cd backend-java; mvn -DskipTests spring-boot:run`
4. 验收：`.\scripts\run-week1-demo.ps1`

无 `DEEPSEEK_API_KEY` 时在 `.env` 设置 `AGENT_MOCK_LLM=true` 即可跑通演示（证据标记为 SYNTHETIC）。
