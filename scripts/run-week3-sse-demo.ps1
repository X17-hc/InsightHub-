# InsightHub 第 3 周：异步任务 + SSE 断线续传 + pause/resume（BaseResponse 信封；SSE 不套信封）
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8080"
$Ws = "workspace-demo"

# Windows PowerShell 5.1 需显式加载；PS7 忽略失败即可
try { Add-Type -AssemblyName System.Net.Http -ErrorAction Stop } catch {}

function Unwrap-Data($wrap) {
    if ($null -eq $wrap) { throw "empty response" }
    if ($null -ne $wrap.code -and [int]$wrap.code -ne 0) {
        throw "biz error code=$($wrap.code) message=$($wrap.message)"
    }
    if ($null -ne $wrap.data) { return $wrap.data }
    return $wrap
}

function Login-Demo {
    $body = @{ username = "demo"; password = "demo123456" } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$Base/api/v1/auth/login" -Method Post -Body $body -ContentType "application/json; charset=utf-8"
    $data = Unwrap-Data $login
    if (-not $data.accessToken) { throw "login failed" }
    return $data.accessToken
}

function Create-AsyncTask([string]$Token, [string]$Query) {
    $headers = @{ Authorization = "Bearer $Token"; "Content-Type" = "application/json; charset=utf-8" }
    $body = @{ query = $Query } | ConvertTo-Json
    return Invoke-WebRequest -Uri "$Base/api/v1/workspaces/$Ws/research/tasks" -Method Post -Headers $headers -Body $body -UseBasicParsing
}

function Read-SseEvents {
    param(
        [string]$Token,
        [string]$TaskId,
        [long]$FromEventNo = 0,
        [int]$MaxEvents = 50,
        [int]$TimeoutSec = 90
    )
    # SSE 不套 BaseResponse；使用 HttpWebRequest 避免程序集差异
    $url = "$Base/api/v1/workspaces/$Ws/research/tasks/$TaskId/events?access_token=$([uri]::EscapeDataString($Token))&fromEventNo=$FromEventNo"
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = "GET"
    $req.Accept = "text/event-stream"
    $req.Timeout = $TimeoutSec * 1000
    $req.ReadWriteTimeout = $TimeoutSec * 1000
    $req.KeepAlive = $true
    if ($FromEventNo -gt 0) {
        $req.Headers.Add("Last-Event-ID", "$FromEventNo")
    }
    $resp = $req.GetResponse()
    if ([int]$resp.StatusCode -ne 200) {
        throw "SSE HTTP $([int]$resp.StatusCode)"
    }
    $stream = $resp.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    $events = New-Object System.Collections.Generic.List[object]
    $dataBuf = New-Object System.Collections.Generic.List[string]
    $currentId = $null
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    try {
        while ([DateTime]::UtcNow -lt $deadline -and $events.Count -lt $MaxEvents) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith("id:")) {
                $currentId = $line.Substring(3).Trim()
            } elseif ($line.StartsWith("data:")) {
                $dataBuf.Add($line.Substring(5).Trim())
            } elseif ($line -eq "") {
                if ($dataBuf.Count -gt 0) {
                    $json = ($dataBuf -join "`n")
                    $dataBuf.Clear()
                    try {
                        $obj = $json | ConvertFrom-Json
                        if ($currentId) { $obj | Add-Member -NotePropertyName sseId -NotePropertyValue $currentId -Force }
                        $events.Add($obj)
                        $t = $obj.type
                        if ($t -eq "TASK_COMPLETED" -or $t -eq "TASK_FAILED" -or ($t -eq "TASK_RESULT" -and $obj.status -in @("COMPLETED", "FAILED", "CANCELLED"))) {
                            break
                        }
                    } catch {}
                }
                $currentId = $null
            }
        }
    } finally {
        $reader.Close()
        $resp.Close()
        $req.Abort()
    }
    return $events
}

Write-Host "=== health ==="
$h = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/health")
if ($h.status -ne "ok") { throw "health failed" }

$token = Login-Demo
Write-Host "=== async create ==="
$createResp = Create-AsyncTask -Token $token -Query "Week3 SSE isolation and resume demo"
if ($createResp.StatusCode -ne 202) { throw "expected 202, got $($createResp.StatusCode)" }
$created = Unwrap-Data ($createResp.Content | ConvertFrom-Json)
$taskId = $created.taskId
Write-Host "taskId=$taskId"

Write-Host "=== SSE first connect (few events then disconnect) ==="
$batch1 = Read-SseEvents -Token $token -TaskId $taskId -FromEventNo 0 -MaxEvents 3 -TimeoutSec 60
if ($batch1.Count -lt 1) { throw "expected at least 1 SSE event" }
$lastId = 0L
foreach ($e in $batch1) {
    if ($e.eventId) { $lastId = [Math]::Max($lastId, [int64]$e.eventId) }
    elseif ($e.sseId) { $lastId = [Math]::Max($lastId, [int64]$e.sseId) }
}
Write-Host "batch1 count=$($batch1.Count) lastId=$lastId"

Write-Host "=== SSE reconnect with fromEventNo=$lastId ==="
$batch2 = Read-SseEvents -Token $token -TaskId $taskId -FromEventNo $lastId -MaxEvents 80 -TimeoutSec 120
$all = @()
$seen = @{}
foreach ($e in (@($batch1) + @($batch2))) {
    $id = if ($e.eventId) { [string]$e.eventId } else { [string]$e.sseId }
    if ($id -and -not $seen.ContainsKey($id)) {
        $seen[$id] = $true
        $all += $e
    }
}
$types = @($all | ForEach-Object { $_.type })
Write-Host "unique events=$($all.Count) types=$($types -join ',')"
$terminal = $false
foreach ($e in $all) {
    if ($e.type -eq "TASK_COMPLETED") { $terminal = $true }
    if ($e.type -eq "TASK_RESULT" -and $e.status -eq "COMPLETED") { $terminal = $true }
}
# 若续传段未含终态，轮询任务详情
if (-not $terminal) {
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 2
        $headers = @{ Authorization = "Bearer $token" }
        $detail = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$taskId" -Headers $headers)
        Write-Host "poll status=$($detail.status)"
        if ($detail.status -eq "COMPLETED") { $terminal = $true; break }
        if ($detail.status -in @("FAILED", "CANCELLED")) { throw "task ended as $($detail.status)" }
    }
}
if (-not $terminal) { throw "task did not complete after SSE resume" }

Write-Host "=== pause / resume on second task ==="
$headers = @{ Authorization = "Bearer $token" }
$pausedOk = $false
for ($attempt = 1; $attempt -le 5; $attempt++) {
    $create2 = Create-AsyncTask -Token $token -Query "Week3 pause resume demo attempt $attempt"
    $task2 = (Unwrap-Data ($create2.Content | ConvertFrom-Json)).taskId
    # MOCK 节点边界有 delay，尽早 pause
    Start-Sleep -Milliseconds 200
    try {
        $pause = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$task2/pause" -Method Post -Headers $headers)
        Write-Host "pause status=$($pause.status) task2=$task2"
    } catch {
        Write-Host "pause attempt $attempt failed: $($_.Exception.Message)"
        continue
    }
    # 等 Python 节点边界落地 PAUSED
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 300
        $detail2 = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$task2" -Headers $headers)
        if ($detail2.status -eq "PAUSED") { $pausedOk = $true; break }
        if ($detail2.status -in @("COMPLETED", "FAILED", "CANCELLED")) { break }
    }
    if ($pausedOk) { break }
    Write-Host "task2 ended as $($detail2.status); retry pause"
}
if (-not $pausedOk) { throw "failed to pause a running task after retries" }

$resume = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$task2/resume" -Method Post -Headers $headers)
Write-Host "resume status=$($resume.status)"
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    $detail2 = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$task2" -Headers $headers)
    Write-Host "resume poll=$($detail2.status)"
    if ($detail2.status -eq "COMPLETED") { break }
    if ($detail2.status -in @("FAILED", "CANCELLED")) { throw "pause/resume task ended $($detail2.status)" }
}
if ($detail2.status -ne "COMPLETED") { throw "pause/resume task not completed" }

Write-Host "=== cancel on third task ==="
$cancelOk = $false
for ($attempt = 1; $attempt -le 5; $attempt++) {
    $create3 = Create-AsyncTask -Token $token -Query "Week3 cancel demo attempt $attempt"
    $task3 = (Unwrap-Data ($create3.Content | ConvertFrom-Json)).taskId
    Start-Sleep -Milliseconds 200
    try {
        $cancel = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$task3/cancel" -Method Post -Headers $headers)
        Write-Host "cancel status=$($cancel.status) task3=$task3"
        $cancelOk = $true
        break
    } catch {
        Write-Host "cancel attempt $attempt failed: $($_.Exception.Message)"
    }
}
if (-not $cancelOk) { throw "failed to cancel a running task after retries" }
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 300
    $detail3 = Unwrap-Data (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$task3" -Headers $headers)
    if ($detail3.status -eq "CANCELLED") { break }
}
if ($detail3.status -ne "CANCELLED") { throw "expected CANCELLED, got $($detail3.status)" }

Write-Host "=== 第 3 周 SSE 验收通过 ==="
