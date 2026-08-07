# 仅用于初始化空数据库；DDL 含 DROP TABLE，不是升级迁移脚本

param(
    [switch]$ForceRecreate
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$MysqlSchema = Join-Path $ProjectRoot "deploy\mysql\init\02_schema.sql"
$PgSchema = Join-Path $ProjectRoot "deploy\postgres\init\02_schema.sql"
$MysqlExe = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
$PsqlExe = "C:\Program Files\PostgreSQL\16\bin\psql.exe"

if (-not (Test-Path $MysqlSchema)) { throw "缺少 $MysqlSchema" }
if (-not (Test-Path $PgSchema)) { throw "缺少 $PgSchema" }

if (-not $ForceRecreate) {
    $mysqlTableCountText = & $MysqlExe -h 127.0.0.1 -P 3306 -u root -p123456 --protocol=tcp -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='insighthub';"
    if ($LASTEXITCODE -ne 0) { throw "MySQL 初始化前检查失败" }
    $env:PGPASSWORD = "123456"
    $pgTableCountText = & $PsqlExe -U insighthub -h 127.0.0.1 -p 5432 -d insighthub_vector -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';"
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 初始化前检查失败" }

    $mysqlTableCount = [int]($mysqlTableCountText | Select-Object -First 1)
    $pgTableCount = [int]($pgTableCountText | Select-Object -First 1)
    if ($mysqlTableCount -gt 0 -or $pgTableCount -gt 0) {
        throw "检测到现有数据表，已拒绝执行破坏性 DDL。升级请使用迁移脚本；确认清空时显式传入 -ForceRecreate。"
    }
}

Write-Host "=== 初始化 MySQL schema ==="
# 使用 cmd 重定向 + utf8mb4，避免 PowerShell 管道导致中文 COMMENT 乱码
$mysqlCmd = "`"$MysqlExe`" -h 127.0.0.1 -P 3306 -u root -p123456 --protocol=tcp --default-character-set=utf8mb4 insighthub < `"$MysqlSchema`""
cmd /c $mysqlCmd
if ($LASTEXITCODE -ne 0) { throw "MySQL schema 执行失败" }

Write-Host "=== 初始化 PostgreSQL schema ==="
$env:PGPASSWORD = "123456"
& $PsqlExe -U insighthub -h 127.0.0.1 -p 5432 -d insighthub_vector -v ON_ERROR_STOP=1 -f $PgSchema
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL schema 执行失败" }

Write-Host "=== 校验表数量 ==="
& $MysqlExe -h 127.0.0.1 -P 3306 -u insighthub -p123456 --protocol=tcp -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='insighthub';"
$env:PGPASSWORD = "123456"
& $PsqlExe -U insighthub -h 127.0.0.1 -p 5432 -d insighthub_vector -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';"

Write-Host "Schema 应用完成"
