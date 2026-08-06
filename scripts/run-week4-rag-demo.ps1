# InsightHub 第 4 周：知识库入库 + RAG 任务 + 引用可追溯 + 跨空间 40300
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8080"
$Ws = "workspace-demo"
$UploadDir = Join-Path $env:TEMP "insighthub-week4"
New-Item -ItemType Directory -Force -Path $UploadDir | Out-Null
$DocPath = Join-Path $UploadDir "week4-kb-fact.md"

# 可检索的确定性事实（供报告/引用命中）
@"
# InsightHub Week4 Knowledge Fact Sheet

InsightHubWeek4SecretToken 是内部知识库验收口令，仅存在于本上传文档。
Spring AI 与 LangChain4j 在多 Agent 编排上的差异，可结合该口令验证检索是否生效。
文档片段应被分块后写入 PGVector，并由 knowledge_research 节点引用。
"@ | Set-Content -Path $DocPath -Encoding UTF8

function Unwrap($wrap) {
    if ($null -eq $wrap) { throw "empty response" }
    if ($null -ne $wrap.code -and [int]$wrap.code -ne 0) {
        throw "biz error code=$($wrap.code) message=$($wrap.message)"
    }
    if ($null -ne $wrap.data) { return $wrap.data }
    return $wrap
}

function Login([string]$User) {
    $body = @{ username = $User; password = "demo123456" } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$Base/api/v1/auth/login" -Method Post -Body $body -ContentType "application/json; charset=utf-8"
    return (Unwrap $login).accessToken
}

Write-Host "=== health ==="
Unwrap (Invoke-RestMethod "$Base/api/v1/health") | Out-Null

$tokenA = Login "demo"
$tokenB = Login "demob"
$headersA = @{ Authorization = "Bearer $tokenA" }
$headersB = @{ Authorization = "Bearer $tokenB" }

Write-Host "=== create KB ==="
$kbBody = @{ name = "Week4 Demo KB"; description = "RAG acceptance" } | ConvertTo-Json
$kb = Unwrap (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/knowledge-bases" -Method Post -Headers $headersA -Body $kbBody -ContentType "application/json; charset=utf-8")
$kbId = $kb.id
if (-not $kbId) { throw "missing kb id" }
Write-Host "kbId=$kbId"

Write-Host "=== upload document ==="
# multipart：使用 .NET HttpClient 兼容 PS 5.1
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $tokenA)
$form = [System.Net.Http.MultipartFormDataContent]::new()
$bytes = [System.IO.File]::ReadAllBytes($DocPath)
$fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
$fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/markdown")
$form.Add($fileContent, "file", "week4-kb-fact.md")
$resp = $client.PostAsync("$Base/api/v1/workspaces/$Ws/knowledge-bases/$kbId/documents", $form).Result
$text = $resp.Content.ReadAsStringAsync().Result
if (-not $resp.IsSuccessStatusCode) { throw "upload HTTP $([int]$resp.StatusCode) $text" }
$uploadWrap = $text | ConvertFrom-Json
$doc = Unwrap $uploadWrap
$docId = $doc.id
Write-Host "docId=$docId status=$($doc.parseStatus)"
$client.Dispose()

Write-Host "=== wait INDEXED ==="
$indexed = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    $detail = Unwrap (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/knowledge-bases/$kbId/documents/$docId" -Headers $headersA)
    Write-Host "parseStatus=$($detail.parseStatus) chunkCount=$($detail.chunkCount)"
    if ($detail.parseStatus -eq "INDEXED") { $indexed = $true; break }
    if ($detail.parseStatus -eq "FAILED") { throw "ingest failed: $($detail.errorMessage)" }
}
if (-not $indexed) { throw "document not INDEXED in time" }

Write-Host "=== sync task with knowledgeBaseIds ==="
$taskBody = @{
    query = "InsightHubWeek4SecretToken 是什么？请结合内部知识库回答"
    knowledgeBaseIds = @($kbId)
} | ConvertTo-Json
$task = Unwrap (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/sync" -Method Post -Headers (@{ Authorization = "Bearer $tokenA"; "Content-Type" = "application/json; charset=utf-8" }) -Body $taskBody)
if ($task.status -ne "COMPLETED") { throw "task not completed: $($task | ConvertTo-Json -Depth 6)" }
if (-not $task.reportMarkdown -or ($task.reportMarkdown -notmatch '\[\d+\]')) {
    throw "report missing citation markers [n]"
}
if ($task.reportMarkdown -notmatch "InsightHubWeek4SecretToken") {
    Write-Host "WARN: secret token not in report text; checking citations API"
}
$taskId = $task.taskId
Write-Host "taskId=$taskId"

Write-Host "=== citations API ==="
$citations = Unwrap (Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/research/tasks/$taskId/citations" -Headers $headersA)
if (-not $citations -or $citations.Count -lt 1) { throw "expected >=1 citation" }
$hasKb = $false
foreach ($c in $citations) {
    if ($c.sourceType -eq "KNOWLEDGE" -or ($c.sourceUri -like "kb://*") -or $c.documentId) { $hasKb = $true }
}
if (-not $hasKb) {
    Write-Host "citations=$($citations | ConvertTo-Json -Depth 5)"
    throw "expected at least one KNOWLEDGE citation"
}
Write-Host "citationCount=$($citations.Count)"

Write-Host "=== demob cannot access KB (40300) ==="
$deny = Invoke-RestMethod -Uri "$Base/api/v1/workspaces/$Ws/knowledge-bases/$kbId" -Headers $headersB
if ([int]$deny.code -ne 40300) { throw "expected 40300, got $($deny.code)" }

Write-Host "=== 第 4 周 RAG 验收通过 ==="
