# 在本机 PostgreSQL 上创建 InsightHub 向量库并启用 pgvector
# 优先读取项目根目录 .env 中的 POSTGRES_* 变量

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $ProjectRoot ".env"
$psql = "C:\Program Files\PostgreSQL\18\bin\psql.exe"

if (-not (Test-Path $psql)) {
    throw "未找到 PostgreSQL 18 的 psql.exe"
}

function Read-DotEnv([string]$Path) {
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $idx = $line.IndexOf("=")
        if ($idx -lt 1) { return }
        $map[$line.Substring(0, $idx).Trim()] = $line.Substring($idx + 1).Trim()
    }
    return $map
}

$envMap = Read-DotEnv $EnvFile
$superUser = if ($envMap['POSTGRES_SUPERUSER']) { $envMap['POSTGRES_SUPERUSER'] } else { "postgres" }
$superPass = $envMap['POSTGRES_SUPERUSER_PASSWORD']
if (-not $superPass) {
    $secure = Read-Host "请输入 PostgreSQL 超级用户($superUser)密码" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $superPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
}

$db = if ($envMap['POSTGRES_DB']) { $envMap['POSTGRES_DB'] } else { "insighthub_vector" }
$user = if ($envMap['POSTGRES_USER']) { $envMap['POSTGRES_USER'] } else { "insighthub" }
$pass = if ($envMap['POSTGRES_PASSWORD']) { $envMap['POSTGRES_PASSWORD'] } else { "123456" }
$port = if ($envMap['POSTGRES_PORT']) { $envMap['POSTGRES_PORT'] } else { "5432" }

$env:PGPASSWORD = $superPass

$roleExists = & $psql -U $superUser -h 127.0.0.1 -p $port -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='$user';"
if (-not $roleExists) {
    & $psql -U $superUser -h 127.0.0.1 -p $port -d postgres -v ON_ERROR_STOP=1 -c "CREATE USER $user WITH PASSWORD '$pass';"
    Write-Host "已创建用户 $user"
} else {
    & $psql -U $superUser -h 127.0.0.1 -p $port -d postgres -v ON_ERROR_STOP=1 -c "ALTER USER $user WITH PASSWORD '$pass';"
    Write-Host "用户 $user 已存在，已更新密码"
}

$dbExists = & $psql -U $superUser -h 127.0.0.1 -p $port -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$db';"
if (-not $dbExists) {
    & $psql -U $superUser -h 127.0.0.1 -p $port -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $db OWNER $user;"
    Write-Host "已创建数据库 $db"
} else {
    Write-Host "数据库 $db 已存在"
}

& $psql -U $superUser -h 127.0.0.1 -p $port -d $db -v ON_ERROR_STOP=1 -c @"
CREATE EXTENSION IF NOT EXISTS vector;
GRANT ALL ON SCHEMA public TO $user;
ALTER DATABASE $db OWNER TO $user;
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
SELECT '[1,2,3]'::vector <-> '[4,5,6]'::vector AS l2_distance;
"@

Write-Host "PostgreSQL / PGVector 初始化完成"
