# 停止本机 InsightHub MySQL 用户目录实例

$ErrorActionPreference = "Continue"
$MysqlAdmin = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqladmin.exe"
$RuntimeIni = Join-Path (Split-Path -Parent $PSScriptRoot) "deploy\runtime\mysql\my.ini"

if (Test-Path $MysqlAdmin) {
    & $MysqlAdmin --defaults-file="$RuntimeIni" -uroot -p123456 --protocol=tcp -P 3306 shutdown 2>$null
}

Start-Sleep -Seconds 1
Get-Process mysqld -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "MySQL 已停止"
