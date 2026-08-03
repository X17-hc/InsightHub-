# InsightHub 基础设施连通性检查

$ErrorActionPreference = "Continue"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Read-DotEnv {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $idx = $line.IndexOf("=")
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim()
        $map[$key] = $val
    }
    return $map
}

$envMap = Read-DotEnv (Join-Path $ProjectRoot ".env")
$ok = $true

Write-Host "===== InsightHub 环境检查 ====="
Write-Host "模式: $($envMap['INSIGHTHUB_INFRA_MODE'])"
Write-Host ""

# 1) MySQL
$mysql = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
$mysqlUser = $envMap['MYSQL_USER']; if (-not $mysqlUser) { $mysqlUser = "insighthub" }
$mysqlPass = $envMap['MYSQL_PASSWORD']; if (-not $mysqlPass) { $mysqlPass = "123456" }
$mysqlDb = $envMap['MYSQL_DATABASE']; if (-not $mysqlDb) { $mysqlDb = "insighthub" }

Write-Host "[MySQL]"
if (-not (Test-Path $mysql)) {
    Write-Host "  FAIL  未找到 mysql.exe"
    $ok = $false
} else {
    $out = & $mysql -h 127.0.0.1 -P 3306 -u $mysqlUser "-p$mysqlPass" --protocol=tcp -N -e "SELECT 'OK'" 2>&1
    if ($LASTEXITCODE -eq 0 -and ($out -match "OK")) {
        Write-Host "  OK    连接成功  db=$mysqlDb user=$mysqlUser"
    } else {
        Write-Host "  FAIL  连接失败: $out"
        Write-Host "        可执行: .\scripts\start-mysql.ps1"
        $ok = $false
    }
}

# 2) Redis
Write-Host "[Redis]"
$redisCli = "C:\Program Files\Redis\redis-cli.exe"
if (-not (Test-Path $redisCli)) {
    $cmd = Get-Command redis-cli -ErrorAction SilentlyContinue
    if ($cmd) { $redisCli = $cmd.Source } else { $redisCli = $null }
}
if (-not $redisCli) {
    Write-Host "  FAIL  未找到 redis-cli"
    $ok = $false
} else {
    $pong = & $redisCli ping 2>&1
    if ($pong -match "PONG") {
        Write-Host "  OK    PONG  ($redisCli)"
    } else {
        Write-Host "  FAIL  ping 失败: $pong"
        $ok = $false
    }
}

# 3) PostgreSQL + pgvector
Write-Host "[PostgreSQL/PGVector]"
$psql = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
$pgUser = $envMap['POSTGRES_USER']; if (-not $pgUser) { $pgUser = "insighthub" }
$pgPass = $envMap['POSTGRES_PASSWORD']; if (-not $pgPass) { $pgPass = "123456" }
$pgDb = $envMap['POSTGRES_DB']; if (-not $pgDb) { $pgDb = "insighthub_vector" }
$pgPort = $envMap['POSTGRES_PORT']; if (-not $pgPort) { $pgPort = "5432" }

if (-not (Test-Path $psql)) {
    Write-Host "  FAIL  未找到 psql.exe"
    $ok = $false
} else {
    $env:PGPASSWORD = $pgPass
    $ver = & $psql -U $pgUser -h 127.0.0.1 -p $pgPort -d $pgDb -tAc "SELECT extversion FROM pg_extension WHERE extname='vector';" 2>&1
    if ($LASTEXITCODE -eq 0 -and $ver) {
        $dist = & $psql -U $pgUser -h 127.0.0.1 -p $pgPort -d $pgDb -tAc "SELECT '[1,2,3]'::vector <-> '[4,5,6]'::vector;" 2>&1
        Write-Host "  OK    vector=$($ver.Trim())  l2=$($dist.Trim())  db=$pgDb"
    } else {
        Write-Host "  FAIL  连接或扩展检查失败: $ver"
        $ok = $false
    }
}

Write-Host ""
if ($ok) {
    Write-Host "结果: 全部通过"
    exit 0
} else {
    Write-Host "结果: 存在失败项，请查看 docs/environment-setup.md"
    exit 1
}
