# InsightHub 第 2 周：工作空间数据隔离验收（BaseResponse：HTTP 多为 200，业务码在 .code）
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8080"

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$ExpectHttp = @(200),
        # 若指定，则校验信封业务码（成功为 0，禁止访问为 40300）
        [int[]]$ExpectBizCode = $null
    )
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    if ($null -ne $Body) {
        $jsonBody = ($Body | ConvertTo-Json -Depth 6 -Compress)
        $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $headers `
            -ContentType "application/json; charset=utf-8" -Body $jsonBody -UseBasicParsing
    } else {
        $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $headers -UseBasicParsing
    }
    $http = [int]$resp.StatusCode
    $json = $null
    if ($resp.Content) { $json = $resp.Content | ConvertFrom-Json }
    if ($ExpectHttp -notcontains $http) {
        throw "期望 HTTP $($ExpectHttp -join '/')，实际 $http ，body=$($resp.Content)"
    }
    if ($null -ne $ExpectBizCode) {
        $biz = [int]$json.code
        if ($ExpectBizCode -notcontains $biz) {
            throw "期望业务码 $($ExpectBizCode -join '/')，实际 $biz ，body=$($resp.Content)"
        }
    }
    return @{ Code = $http; Body = $json; Data = $json.data }
}

Write-Host "=== health ==="
$h = Invoke-Json -Method GET -Url "$Base/api/v1/health" -ExpectBizCode @(0)
if ($h.Data.status -ne "ok") { throw "health failed" }

Write-Host "=== login demo / demob ==="
$loginA = Invoke-Json -Method POST -Url "$Base/api/v1/auth/login" `
    -Body @{ username = "demo"; password = "demo123456" } -ExpectBizCode @(0)
$loginB = Invoke-Json -Method POST -Url "$Base/api/v1/auth/login" `
    -Body @{ username = "demob"; password = "demo123456" } -ExpectBizCode @(0)
$tokenA = $loginA.Data.accessToken
$tokenB = $loginB.Data.accessToken
$wsA = "workspace-demo"
$wsB = "workspace-demo-b"
if (-not $tokenA -or -not $tokenB) { throw "login token missing" }

Write-Host "=== A 创建任务于 workspace-A（/sync） ==="
$taskA = Invoke-Json -Method POST -Url "$Base/api/v1/workspaces/$wsA/research/tasks/sync" `
    -Token $tokenA -Body @{ query = "Week2 isolation demo for workspace A" } -ExpectBizCode @(0)
if ($taskA.Data.status -ne "COMPLETED") { throw "task A not completed: $($taskA.Data | ConvertTo-Json -Depth 5)" }
$taskIdA = $taskA.Data.taskId
Write-Host "taskA=$taskIdA"

Write-Host "=== B 访问 workspace-A 应业务码 40300 ==="
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/research/tasks" -Token $tokenB -ExpectBizCode @(40300) | Out-Null
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/research/tasks/$taskIdA" -Token $tokenB -ExpectBizCode @(40300) | Out-Null

Write-Host "=== B 在 workspace-B 创建任务（/sync） ==="
$taskB = Invoke-Json -Method POST -Url "$Base/api/v1/workspaces/$wsB/research/tasks/sync" `
    -Token $tokenB -Body @{ query = "Week2 isolation demo for workspace B" } -ExpectBizCode @(0)
$taskIdB = $taskB.Data.taskId
Write-Host "taskB=$taskIdB"

Write-Host "=== A 访问 workspace-B 应业务码 40300 ==="
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsB/research/tasks" -Token $tokenA -ExpectBizCode @(40300) | Out-Null
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsB/research/tasks/$taskIdB" -Token $tokenA -ExpectBizCode @(40300) | Out-Null

Write-Host "=== A 可读自己的任务 ==="
$mine = Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/research/tasks/$taskIdA" -Token $tokenA -ExpectBizCode @(0)
if ($mine.Data.taskId -ne $taskIdA) { throw "A cannot read own task" }

Write-Host "=== Agent 列表隔离：B 读 A 的 agents 应 40300 ==="
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/agents" -Token $tokenB -ExpectBizCode @(40300) | Out-Null
$agentsA = Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/agents" -Token $tokenA -ExpectBizCode @(0)
if (-not $agentsA.Data -or $agentsA.Data.Count -lt 3) { throw "workspace A should have >=3 seeded agents" }

Write-Host "=== 第 2 周隔离验收通过 ==="
