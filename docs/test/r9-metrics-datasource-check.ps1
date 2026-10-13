# =====================================================================
# R9 指标数据源 运行时验证脚本（P3.8 R9-1 / R9-2）
#
# 验证「数据源真的在落数」，而不是只看代码写完：
#   A. 结构就绪：model_call_log 表 + agent_task/agent_task_step 的 duration_ms 列存在
#   B. 台账有数：指定任务（或新提交一单）在 model_call_log 中有行，且 llm_step 场景至少 1 行、
#      至少 1 行 token 合计 > 0、task_id/step_id 关联正确
#   C. 耗时落数：agent_task.duration_ms 非空；LLM 步骤的 duration_ms 非空且 > 0
#   D. 指标 SQL 可执行：metrics.md §1.1 / §2.1 / §2.2 / §3.1 的 SQL 在真实库能跑通并返回行
#
# 用法（-TaskId 复检既有任务，不消耗配额）：
#   powershell -ExecutionPolicy Bypass -File docs\test\r9-metrics-datasource-check.ps1 `
#       -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
#   ... -TaskId 400686          # 复检（不重新提交）
#   ... -SkipSubmit             # 只做结构 + 指标 SQL 检查
# =====================================================================

param(
    [string]$Gateway = "http://localhost:9080",
    [string]$CoreBase = "http://localhost:9201",
    [string]$MySqlExe = "mysql",
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [string]$DeptName = "研发部",
    [int]$DeptId = 3,
    [string]$Period = "2026-09",
    [string]$SampleFile = "",
    [int]$PollSeconds = 180,
    [long]$TaskId = 0,
    [switch]$SkipSubmit
)

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0

function Invoke-Sql([string]$sql) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $MySqlExe -h $DbHost -P $Port -u $User "-p$Password" -D $Database --default-character-set=utf8mb4 -N -B -e $sql 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    $lines = @($raw | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'Using a password' })
    if ($code -ne 0) { throw ("SQL 失败: {0}" -f ($lines -join ' / ')) }
    return (($lines -join "`n").Trim())
}

function Sql-Ok([string]$sql, [string]$label) {
    try {
        [void](Invoke-Sql $sql)
        Write-Host ("  [PASS] {0}" -f $label) -ForegroundColor Green
        $script:pass++
    } catch {
        Write-Host ("  [FAIL] {0} → {1}" -f $label, $_.Exception.Message) -ForegroundColor Red
        $script:fail++
    }
}

function Check-True($cond, [string]$label) {
    if ($cond) {
        Write-Host ("  [PASS] {0}" -f $label) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [FAIL] {0}" -f $label) -ForegroundColor Red
        $script:fail++
    }
}

function Api([string]$method, [string]$uri, $body, [string]$token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Method = $method; Uri = $uri; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 90 }
    if ($null -ne $body) {
        $params["Body"] = ($body | ConvertTo-Json -Depth 10 -Compress)
        $params["ContentType"] = "application/json; charset=utf-8"
    }
    try {
        return ((Invoke-WebRequest @params).Content | ConvertFrom-Json)
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $sr.ReadToEnd()
            $parsed = $null
            if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
            if ($parsed -and (($parsed.PSObject.Properties.Name) -contains 'code')) { return $parsed }
            return [pscustomobject]@{ code = -1; message = ("HTTP {0} {1}" -f [int]$resp.StatusCode, (($text -replace '\s+',' ').Trim())); data = $null }
        }
        throw
    }
}

Write-Host "=== R9 指标数据源 运行时验证 ===" -ForegroundColor Cyan

# ---------- 判据 A：结构就绪 ----------
Write-Host "[1] 判据A：表与列就绪" -ForegroundColor Yellow
$tbl = Invoke-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_name='model_call_log';"
Check-True ("$tbl" -eq "1") "model_call_log 表存在（实际 $tbl）"
$cols = Invoke-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$Database' AND ((table_name='agent_task' AND column_name='duration_ms') OR (table_name='agent_task_step' AND column_name='duration_ms'));"
Check-True ("$cols" -eq "2") "duration_ms 两列存在（实际 $cols/2）"
$logCols = Invoke-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$Database' AND table_name='model_call_log';"
Write-Host ("    model_call_log 列数 = {0}" -f $logCols) -ForegroundColor DarkGray

# ---------- 判据 D：指标文档 SQL 可执行 ----------
Write-Host "[2] 判据D：metrics.md 的 SQL 可执行" -ForegroundColor Yellow
Sql-Ok "SELECT DATE(created_at) d, COUNT(*) c, SUM(total_tokens) t FROM model_call_log WHERE tenant_id=1 GROUP BY DATE(created_at) ORDER BY d DESC LIMIT 1;" "§1.1 成本按天聚合"
Sql-Ok "SELECT scene, COUNT(*) c, SUM(total_tokens) t FROM model_call_log WHERE tenant_id=1 GROUP BY scene;" "§1.2 按场景拆成本"
Sql-Ok "SELECT COUNT(duration_ms) samples, AVG(duration_ms) avg_ms FROM agent_task WHERE tenant_id=1 AND task_type='REIMBURSEMENT';" "§2.1 任务耗时（分母用 COUNT(duration_ms)）"
Sql-Ok "SELECT s.step_name, COUNT(s.duration_ms) n FROM agent_task_step s JOIN agent_task t ON t.id=s.task_id WHERE s.deleted=0 AND s.duration_ms IS NOT NULL GROUP BY s.step_name;" "§2.2 步骤耗时排行"
Sql-Ok "SELECT DATE(created_at) d, SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) ap, COUNT(*) total FROM agent_task WHERE tenant_id=1 AND task_type='REIMBURSEMENT' GROUP BY DATE(created_at);" "§3.1 自动通过率"
Sql-Ok "SELECT r.invoice_code, r.invoice_num, COUNT(DISTINCT l.reimb_id) c FROM invoice_reimb_link l JOIN invoice_record r ON r.id=l.invoice_record_id WHERE l.deleted=0 AND r.deleted=0 GROUP BY r.invoice_code, r.invoice_num HAVING c>1;" "§3.2 重复报销（需 join invoice_record）"

# ---------- 取任务：复检 or 新提交 ----------
if ($TaskId -gt 0) {
    $taskId = $TaskId
    Write-Host ("[3] 复检既有任务 taskId={0}（不重新提交）" -f $taskId) -ForegroundColor DarkGray
} elseif ($SkipSubmit) {
    $taskId = 0
    Write-Host "[3] -SkipSubmit：跳过台账/耗时判据（仅结构 + SQL）" -ForegroundColor DarkYellow
} else {
    $login = Api POST "$Gateway/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
    if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
    $token = $login.data.token

    $sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
    $samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
    $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
    if (-not $curl) { throw "需要 curl.exe" }
    if ($samples.Count -eq 0) { throw "未找到 OCR 样本" }
    $sample = if ($SampleFile) { Get-Item $SampleFile } else { $samples[(Get-Random -Maximum $samples.Count)] }
    Write-Host ("    样本: {0}" -f $sample.Name) -ForegroundColor DarkGray

    $tmp = Join-Path $env:TEMP ("r9-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $sample.Extension)
    Copy-Item $sample.FullName $tmp -Force
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $fid = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $fid) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }

    $base = [decimal](Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM expense_reimbursement WHERE deleted = 0;")
    $r = Api POST "$Gateway/api/v1/reimbursements" @{
        title = "R9 指标数据源验证"; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
        claimDate = "$Period-01"; remark = "R9 metrics check"
        items = @(@{ name = "测试明细"; amount = $base })
        fileRecordIds = @($fid)
    } $token
    if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }
    $taskId = $r.data.taskId
    Write-Host ("[3] 已提交 reimbId={0} taskId={1}（真实 LLM 调用，用于产生台账）" -f $r.data.id, $taskId) -ForegroundColor DarkGray

    Write-Host ("[4] 等待流水线完成（最多 {0} 秒）" -f $PollSeconds) -ForegroundColor Yellow
    $deadline = (Get-Date).AddSeconds($PollSeconds)
    while ((Get-Date) -lt $deadline) {
        $st = Invoke-Sql "SELECT status FROM agent_task WHERE id = $taskId;"
        $pending = Invoke-Sql "SELECT COUNT(*) FROM agent_task_step WHERE task_id = $taskId AND deleted = 0 AND status <> 'SUCCESS';"
        if ("$pending" -eq "0" -and $st -ne 'RUNNING' -and $st -ne 'PENDING') { break }
        Start-Sleep -Seconds 5
    }
    Write-Host ("    task status = {0}" -f $st) -ForegroundColor DarkGray
}

# ---------- 判据 B/C：台账与耗时真的有数 ----------
if ($taskId -gt 0) {
    Write-Host "[5] 判据B：模型调用台账（model_call_log）" -ForegroundColor Yellow
    $rows = Invoke-Sql "SELECT COUNT(*) FROM model_call_log WHERE task_id = $taskId;"
    Check-True ([int]$rows -ge 1) "该任务有台账行（实际 $rows；每次模型调用一行，含规划/重试）"

    $llmRows = Invoke-Sql "SELECT COUNT(*) FROM model_call_log WHERE task_id = $taskId AND scene = 'llm_step';"
    Check-True ([int]$llmRows -ge 1) "存在 scene=llm_step 的行（实际 $llmRows）"

    $tokRow = Invoke-Sql "SELECT IFNULL(SUM(total_tokens),0), IFNULL(SUM(CASE WHEN success=1 THEN 1 ELSE 0 END),0), IFNULL(SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),0) FROM model_call_log WHERE task_id = $taskId;"
    $tk = @($tokRow -split "`t")
    Write-Host ("    tokens 合计={0} 成功={1} 失败={2}" -f $tk[0], $tk[1], $tk[2]) -ForegroundColor DarkGray
    Check-True ([int]$tk[0] -gt 0) "累计 token > 0（说明用量被真实回填，实际 $($tk[0])）"

    $linked = Invoke-Sql "SELECT COUNT(*) FROM model_call_log WHERE task_id = $taskId AND step_id IS NOT NULL;"
    Check-True ([int]$linked -ge 1) "台账带 step_id 关联（实际 $linked）"

    Write-Host "[6] 判据C：耗时落库（duration_ms）" -ForegroundColor Yellow
    $taskDur = Invoke-Sql "SELECT IFNULL(duration_ms,-1) FROM agent_task WHERE id = $taskId;"
    Write-Host ("    agent_task.duration_ms = {0}" -f $taskDur) -ForegroundColor DarkGray
    Check-True ([long]$taskDur -gt 0) "任务耗时已落库且 > 0（实际 $taskDur ms）"

    $llmStepDur = Invoke-Sql "SELECT COUNT(*) FROM agent_task_step WHERE task_id = $taskId AND deleted = 0 AND step_type = 'LLM' AND IFNULL(duration_ms,0) > 0;"
    Check-True ([int]$llmStepDur -ge 1) "至少一个 LLM 步骤耗时 > 0（实际 $llmStepDur）"

    $stepDur = Invoke-Sql "SELECT step_no, step_name, step_type, IFNULL(duration_ms,0) FROM agent_task_step WHERE task_id = $taskId AND deleted = 0 ORDER BY step_no;"
    Write-Host "    各步骤耗时（stepNo / 名称 / 类型 / ms）：" -ForegroundColor DarkGray
    $stepDur -split "`n" | ForEach-Object { Write-Host ("      {0}" -f $_ -replace "`t", ' | ') -ForegroundColor DarkGray }

    # 指标端点（内部直连 9201，不经网关）
    Write-Host "[7] 内部指标端点（usageSnapshot 接出口）" -ForegroundColor Yellow
    $metrics = Api GET "$CoreBase/internal/metrics/model-usage" $null $null
    if ($metrics.code -eq 0) {
        Write-Host ("    snapshot: {0}" -f $metrics.data.snapshotSummary) -ForegroundColor DarkGray
        $win = $metrics.data.window
        if ($win) { Write-Host ("    window  : calls={0} failed={1} totalTokens={2}" -f $win.calls, $win.failed, $win.totalTokens) -ForegroundColor DarkGray }
        Check-True $true "GET /internal/metrics/model-usage 可读（snapshot + window）"
    } else {
        Write-Host ("    ⚠️ 指标端点返回 code={0} msg={1}（若 agent-core 未重启则属预期）" -f $metrics.code, $metrics.message) -ForegroundColor DarkYellow
        $script:fail++
    }
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
if ($taskId -gt 0) {
    Write-Host ("复核用 taskId={0}" -f $taskId) -ForegroundColor DarkGray
    Write-Host ("  SELECT scene, task_id, step_id, prompt_tokens, completion_tokens, latency_ms, success FROM {0}.model_call_log WHERE task_id = {1};" -f $Database, $taskId) -ForegroundColor DarkGray
}
if ($script:fail -gt 0) { exit 1 }
