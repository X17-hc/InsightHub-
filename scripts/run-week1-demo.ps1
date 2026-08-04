# InsightHub 第 1 周端到端演示（第 2 周起需 JWT + workspace 路径）

$ErrorActionPreference = "Stop"
$JavaBase = "http://127.0.0.1:8080"
$query = "比较 Spring AI 和 LangChain4j 的多 Agent 能力"
$workspaceId = "workspace-demo"

Write-Host "=== 检查 Java 健康 ==="
$health = Invoke-RestMethod -Uri "$JavaBase/api/v1/health" -Method Get
if ($health.status -ne "ok") { throw "Java health 异常: $($health | ConvertTo-Json)" }

Write-Host "=== 登录 demo ==="
$loginBody = @{ username = "demo"; password = "demo123456" } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$JavaBase/api/v1/auth/login" -Method Post -Body $loginBody -ContentType "application/json; charset=utf-8"
$token = $login.accessToken
if (-not $token) { throw "login failed" }

Write-Host "=== 提交研究任务 ==="
$headers = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json; charset=utf-8" }
$body = @{ query = $query } | ConvertTo-Json
$resp = Invoke-RestMethod -Uri "$JavaBase/api/v1/workspaces/$workspaceId/research/tasks" -Method Post -Headers $headers -Body $body

Write-Host "taskId=$($resp.taskId) status=$($resp.status)"
if ($resp.status -ne "COMPLETED") {
    throw "任务未完成: $($resp | ConvertTo-Json -Depth 6)"
}
if (-not $resp.reportMarkdown -or -not $resp.reportMarkdown.TrimStart().StartsWith("#")) {
    throw "reportMarkdown 缺少 Markdown 标题"
}

$types = @($resp.events | ForEach-Object { $_.type })
foreach ($need in @("PLAN_CREATED", "NODE_COMPLETED", "TASK_COMPLETED")) {
    if ($types -notcontains $need) {
        throw "缺少事件类型: $need ; 实际=$($types -join ',')"
    }
}

Write-Host "=== 验收通过 ==="
Write-Host "事件数=$($resp.events.Count)"
Write-Host "报告预览:"
Write-Host ($resp.reportMarkdown.Substring(0, [Math]::Min(400, $resp.reportMarkdown.Length)))
