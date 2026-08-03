# 启动本机 InsightHub MySQL（用户目录数据实例，非 Windows 系统服务）
# 说明：因无管理员权限安装 MySQL Windows Service，数据目录放在 deploy/runtime/mysql

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Runtime = Join-Path $ProjectRoot "deploy\runtime\mysql"
$Ini = Join-Path $Runtime "my.ini"
$Bin = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe"
$MysqlAdmin = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqladmin.exe"

if (-not (Test-Path $Bin)) {
    throw "未找到 mysqld.exe，请先安装 MySQL Server 8.4"
}
if (-not (Test-Path $Ini)) {
    throw "未找到 $Ini，请先完成环境初始化"
}

# 已在监听则直接返回
$listening = netstat -ano | Select-String ":3306\s+.*LISTENING"
if ($listening) {
    Write-Host "MySQL 已在 3306 端口运行"
    exit 0
}

Write-Host "正在启动 MySQL..."
Start-Process -FilePath $Bin -ArgumentList "--defaults-file=`"$Ini`"" -WindowStyle Hidden | Out-Null

for ($i = 0; $i -lt 30; $i++) {
    & $MysqlAdmin --defaults-file="$Ini" -uroot -p123456 --protocol=tcp -P 3306 ping 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "MySQL 已就绪 (127.0.0.1:3306)"
        exit 0
    }
    Start-Sleep -Seconds 1
}

throw "MySQL 启动超时，请查看 deploy/runtime/mysql/logs/mysqld.err"
