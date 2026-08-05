# InsightHub 第 2 周：工作空间数据隔离验收（不提交 Git）
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8080"

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$ExpectStatus = @(200)
    )
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    try {
        if ($null -ne $Body) {
            $jsonBody = ($Body | ConvertTo-Json -Depth 6 -Compress)
            $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $headers `
                -ContentType "application/json; charset=utf-8" -Body $jsonBody -UseBasicParsing
        } else {
            $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $headers -UseBasicParsing
        }
        $code = [int]$resp.StatusCode
        $json = $null
        if ($resp.Content) { $json = $resp.Content | ConvertFrom-Json }
        if ($ExpectStatus -notcontains $code) {
            throw "期望 HTTP $($ExpectStatus -join '/')，实际 $code ，body=$($resp.Content)"
        }
        return @{ Code = $code; Body = $json }
    } catch {
        $ex = $_.Exception
        $respObj = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $text = $_.ErrorDetails.Message
            # 尝试从 WebException 取状态码
            if ($ex.Response) { $respObj = $ex.Response }
        } elseif ($ex.Response) {
            $respObj = $ex.Response
            $stream = $respObj.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $text = $reader.ReadToEnd()
            $reader.Close()
        } else {
            throw
        }

        if ($respObj) {
            $code = [int]$respObj.StatusCode
        } elseif ($_.Exception.Message -match '(\d{3})') {
            $code = [int]$Matches[1]
            $text = $_.ErrorDetails.Message
        } else {
            throw
        }

        if ($ExpectStatus -contains $code) {
            $json = $null
            try { $json = $text | ConvertFrom-Json } catch {}
            return @{ Code = $code; Body = $json; Raw = $text }
        }
        throw "期望 HTTP $($ExpectStatus -join '/')，实际 $code ，body=$text"
    }
}

Write-Host "=== health ==="
$h = Invoke-Json -Method GET -Url "$Base/api/v1/health"
if ($h.Body.status -ne "ok") { throw "health failed" }

Write-Host "=== login demo / demob ==="
$loginA = Invoke-Json -Method POST -Url "$Base/api/v1/auth/login" -Body @{ username = "demo"; password = "demo123456" }
$loginB = Invoke-Json -Method POST -Url "$Base/api/v1/auth/login" -Body @{ username = "demob"; password = "demo123456" }
$tokenA = $loginA.Body.accessToken
$tokenB = $loginB.Body.accessToken
$wsA = "workspace-demo"
$wsB = "workspace-demo-b"
if (-not $tokenA -or -not $tokenB) { throw "login token missing" }

Write-Host "=== A 创建任务于 workspace-A ==="
$taskA = Invoke-Json -Method POST -Url "$Base/api/v1/workspaces/$wsA/research/tasks" `
    -Token $tokenA -Body @{ query = "Week2 isolation demo for workspace A" }
if ($taskA.Body.status -ne "COMPLETED") { throw "task A not completed: $($taskA.Body | ConvertTo-Json -Depth 5)" }
$taskIdA = $taskA.Body.taskId
Write-Host "taskA=$taskIdA"

Write-Host "=== B 访问 workspace-A 应 403 ==="
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/research/tasks" -Token $tokenB -ExpectStatus @(403) | Out-Null
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/research/tasks/$taskIdA" -Token $tokenB -ExpectStatus @(403) | Out-Null

Write-Host "=== B 在 workspace-B 创建任务 ==="
$taskB = Invoke-Json -Method POST -Url "$Base/api/v1/workspaces/$wsB/research/tasks" `
    -Token $tokenB -Body @{ query = "Week2 isolation demo for workspace B" }
$taskIdB = $taskB.Body.taskId
Write-Host "taskB=$taskIdB"

Write-Host "=== A 访问 workspace-B 应 403 ==="
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsB/research/tasks" -Token $tokenA -ExpectStatus @(403) | Out-Null
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsB/research/tasks/$taskIdB" -Token $tokenA -ExpectStatus @(403) | Out-Null

Write-Host "=== A 可读自己的任务 ==="
$mine = Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/research/tasks/$taskIdA" -Token $tokenA
if ($mine.Body.taskId -ne $taskIdA) { throw "A cannot read own task" }

Write-Host "=== Agent 列表隔离：B 读 A 的 agents 应 403 ==="
Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/agents" -Token $tokenB -ExpectStatus @(403) | Out-Null
$agentsA = Invoke-Json -Method GET -Url "$Base/api/v1/workspaces/$wsA/agents" -Token $tokenA
if (-not $agentsA.Body -or $agentsA.Body.Count -lt 3) { throw "workspace A should have >=3 seeded agents" }

Write-Host "=== 第 2 周隔离验收通过 ==="
