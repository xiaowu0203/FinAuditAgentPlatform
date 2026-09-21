# =====================================================================
# R8-3 进度端点 运行时验证脚本（P3.8 R8-3）
#
# 验证「进度百分比 / 当前步骤 / 预计剩余时间」在真实运行时可用，且 ETA 与库内历史基线**同口径**：
#   A. 端点可用：GET /api/v1/tasks/{id}/progress 返回 R 结构、字段齐全、含中文文案
#   B. 运行中快照：高频轮询能捕获 RUNNING 态；progressPct 单调不减且 = round(finished/total*100,1)；
#      currentStepName 非空；estimatedTotalMs = elapsedMs + estimatedRemainingMs
#   C. 基线同口径（关键判据）：RUNNING 快照的 estimatedRemainingMs / samples 必须等于
#      「用库内 SQL 对未完成步骤逐键累加其历史平均耗时」的结果（键 = 类型|工具|角色）
#   D. 终态定格：progressPct=100、estimatedRemainingMs=0、estimateSource=FIXED，
#      且 elapsedMs 与 agent_task.duration_ms 完全一致
#
# 用法（会真实提交一单，消耗 LLM/OCR 配额）：
#   powershell -ExecutionPolicy Bypass -File docs\test\r8-progress-endpoint-check.ps1 `
#       -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
# 复检既有任务（不重新提交、不消耗配额）：
#   ... -TaskId 30003
# =====================================================================

param(
    [string]$Gateway = "http://localhost:9080",
    [string]$MySqlExe = "mysql",
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [string]$DeptName = "研发部",
    [int]$DeptId = 3,
    [string]$ClaimDate = "2026-09-01",
    [string]$SampleFile = "",
    [int]$PollMs = 400,
    [int]$TimeoutSeconds = 120,
    [long]$TaskId = 0
)

$ErrorActionPreference = "Stop"
# 中文可读性：控制台按 UTF-8 输出（否则中文断言 PASS 但提示文案显示为乱码，排查时极易误判）
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$script:pass = 0
$script:fail = 0
$script:skip = 0

# 与 TaskProgressService 保持一致的缺省单步耗时（无历史样本时的兜底）
$DEFAULT_TOOL_MS = 1500
$DEFAULT_LLM_MS = 2500

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

function Check-True($cond, [string]$label) {
    if ($cond) {
        Write-Host ("  [PASS] {0}" -f $label) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [FAIL] {0}" -f $label) -ForegroundColor Red
        $script:fail++
    }
}

function Note-Skip([string]$label) {
    Write-Host ("  [SKIP] {0}" -f $label) -ForegroundColor DarkYellow
    $script:skip++
}

# ⚠️ 中文解码陷阱（本脚本实测踩到，务必保留此实现）：
#   本仓 JSON 响应头是 `Content-Type: application/json`（**不带 charset**）。按 HTTP 规范，
#   无 charset 等价于 ISO-8859-1，于是 Windows PowerShell 5.1 的 Invoke-WebRequest 会把
#   UTF-8 的中文字节按 Latin-1 解码成 `å·²å®Œæˆ` 这类乱码 → 任何「按字面比对中文」的断言都会误判
#   （R8-3 首轮即出现「终态文案可读」FAIL，纯属脚本侧解码问题）。
#   浏览器 / Jackson / Feign 对 application/json 默认按 UTF-8 解释，故**产品侧无需改动**，
#   正确做法在本脚本：显式取原始字节按 UTF-8 解码。
function ConvertFrom-Utf8Json($bytes) {
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return ($text | ConvertFrom-Json)
}

function Read-AllBytes($stream) {
    $ms = New-Object System.IO.MemoryStream
    $stream.CopyTo($ms)
    return $ms.ToArray()
}

function Api([string]$method, [string]$uri, $body, [string]$token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Method = $method; Uri = $uri; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $body) {
        $params["Body"] = ($body | ConvertTo-Json -Depth 10 -Compress)
        $params["ContentType"] = "application/json; charset=utf-8"
    }
    try {
        $resp = Invoke-WebRequest @params
        return (ConvertFrom-Utf8Json $resp.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $text = [System.Text.Encoding]::UTF8.GetString((Read-AllBytes $resp.GetResponseStream()))
            $parsed = $null
            if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
            if ($parsed -and (($parsed.PSObject.Properties.Name) -contains 'code')) { return $parsed }
            return [pscustomobject]@{ code = -1; message = ("HTTP {0} {1}" -f [int]$resp.StatusCode, (($text -replace '\s+',' ').Trim())); data = $null }
        }
        throw
    }
}

# 进度接口的可用读法：失败时返回 null（轮询期间网络抖动不应中断采集）
function Get-Progress([string]$uri, [string]$token) {
    try {
        $r = Api GET $uri $null $token
        if ($r.code -eq 0) { return $r.data }
        Write-Host ("    ⚠️ /progress 返回 code={0} msg={1}" -f $r.code, $r.message) -ForegroundColor DarkYellow
        return $null
    } catch {
        return $null
    }
}

Write-Host "=== R8-3 进度端点 运行时验证 ===" -ForegroundColor Cyan

# ---------- 登录 ----------
$login = Api POST "$Gateway/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token

# ---------- 取任务：新提交 or 复检 ----------
$snapshots = New-Object System.Collections.ArrayList
if ($TaskId -gt 0) {
    $taskId = $TaskId
    Write-Host ("[1] 复检既有任务 taskId={0}（不重新提交）" -f $taskId) -ForegroundColor DarkGray
    $p = Get-Progress "$Gateway/api/v1/tasks/$taskId/progress" $token
    if ($p) { [void]$snapshots.Add($p) }
} else {
    $sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
    $samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
    $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
    if (-not $curl) { throw "需要 curl.exe" }
    if ($samples.Count -eq 0) { throw "未找到 OCR 样本" }
    $sample = if ($SampleFile) { Get-Item $SampleFile } else { $samples[(Get-Random -Maximum $samples.Count)] }
    Write-Host ("    样本: {0}" -f $sample.Name) -ForegroundColor DarkGray

    $tmp = Join-Path $env:TEMP ("r8-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $sample.Extension)
    Copy-Item $sample.FullName $tmp -Force
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $fid = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $fid) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }

    $base = [decimal](Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM expense_reimbursement WHERE deleted = 0;")
    $r = Api POST "$Gateway/api/v1/reimbursements" @{
        title = "R8-3 进度端点验证"; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
        claimDate = $ClaimDate; remark = "R8-3 progress check"
        items = @(@{ name = "测试明细"; amount = $base })
        fileRecordIds = @($fid)
    } $token
    if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }
    $taskId = $r.data.taskId
    Write-Host ("[1] 已提交 reimbId={0} taskId={1}（真实 LLM 调用）" -f $r.data.id, $taskId) -ForegroundColor DarkGray

    Write-Host ("[2] 高频轮询 /progress（每 {0} ms，最多 {1} 秒）" -f $PollMs, $TimeoutSeconds) -ForegroundColor Yellow
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $p = Get-Progress "$Gateway/api/v1/tasks/$taskId/progress" $token
        if ($p) {
            [void]$snapshots.Add($p)
            $last = $p
            if ($p.status -notin @('PENDING', 'RUNNING')) { break }
        }
        Start-Sleep -Milliseconds $PollMs
    }
    if (-not $last) { throw "未取到任何进度快照（端点不可用？）" }
    Write-Host ("    采集 {0} 次，末态 status={1}" -f $snapshots.Count, $last.status) -ForegroundColor DarkGray
}

if ($snapshots.Count -eq 0) { throw "无进度快照，无法验证" }

# ---------- 判据 A：契约字段齐全 ----------
Write-Host "[3] 判据A：端点契约（R 结构 + 字段齐全）" -ForegroundColor Yellow
$s0 = $snapshots[0]
$fields = @('id', 'taskNo', 'status', 'statusText', 'totalSteps', 'finishedSteps', 'progressPct',
            'currentStepName', 'elapsedMs', 'estimatedRemainingMs', 'estimatedTotalMs', 'estimateSource',
            'samples', 'correctionCount', 'message')
$names = $s0.PSObject.Properties.Name
$missing = @($fields | Where-Object { $names -notcontains $_ })
Check-True ($missing.Count -eq 0) ("进度 VO 字段齐全（含 statusText/estimateSource/samples/correctionCount；缺: {0}；若缺 correctionCount 请确认 agent-core 已重启）" -f ($missing -join ','))
Check-True ($s0.id -eq $taskId) ("id 与请求一致（{0}）" -f $s0.id)
Check-True (-not ($names -contains 'result')) "出参为轻量 VO（不含 result/selfCheckResult 等重字段）"
Check-True (-not [string]::IsNullOrWhiteSpace($s0.statusText)) ("statusText 为中文文案（{0}）" -f $s0.statusText)

# ---------- 快照一览（去重展示） ----------
Write-Host "[4] 采集到的进度快照（去重后按出现顺序）" -ForegroundColor Yellow
$seen = @{}
foreach ($s in $snapshots) {
    $k = "{0}|{1}|{2}|{3}" -f $s.status, $s.progressPct, $s.estimateSource, $s.estimatedRemainingMs
    if ($seen.ContainsKey($k)) { continue }
    $seen[$k] = $true
    Write-Host ("    {0,-17} pct={1,5} 步={2}/{3} 当前={4,-14} 已耗时={5,6}ms 剩余={6,7}ms 依据={7,-8} 样本={8,-3} {9}" -f `
        $s.status, $s.progressPct, $s.finishedSteps, $s.totalSteps, $s.currentStepName, $s.elapsedMs, `
        $s.estimatedRemainingMs, $s.estimateSource, $s.samples, $s.message) -ForegroundColor DarkGray
}

# ---------- 判据 B：运行中快照自洽 ----------
Write-Host "[5] 判据B：运行中快照自洽" -ForegroundColor Yellow
$running = @($snapshots | Where-Object { $_.status -eq 'RUNNING' })
if ($running.Count -eq 0) {
    if ($TaskId -gt 0) {
        Note-Skip "复检模式：该任务已到终态，无 RUNNING 快照（前置条件不满足，非产品缺陷）"
    } else {
        Check-True $false "轮询期间捕获到 RUNNING 快照（流水线可能过快，可减小 -PollMs）"
    }
} else {
    Check-True $true ("捕获到 RUNNING 快照 {0} 个" -f $running.Count)

    # progressPct 回退是**合法的真实状态**，不是缺陷：R5 自校验闸口判定不一致时会重跑风险相关步骤，
    # 这些步骤状态从 SUCCESS 复位 → finishedSteps 真的减少（实测 87.5% → 62.5% 再爬回）。
    # 端点必须如实反映（不能为了让进度条好看而谎报单调递增），故判据分三档：
    #   无回退                        → PASS
    #   有回退 且 correctionCount ≥ 1 → PASS（机制可解释：自主纠错重跑）
    #   有回退 且 correctionCount = 0 → FAIL（无任何机制可解释，须排查）
    #   有回退 但响应无该字段        → SKIP（agent-core 未重启，拿不到纠错次数）
    $pcts = @($snapshots | ForEach-Object { [double]$_.progressPct })
    $decreases = @()
    for ($i = 1; $i -lt $pcts.Count; $i++) {
        if ($pcts[$i] -lt $pcts[$i - 1]) { $decreases += $i }
    }
    $lastSnap = $snapshots[$snapshots.Count - 1]
    $hasCc = ($lastSnap.PSObject.Properties.Name) -contains 'correctionCount'
    $cc = if ($hasCc -and $null -ne $lastSnap.correctionCount) { [int]$lastSnap.correctionCount } else { 0 }
    if ($decreases.Count -eq 0) {
        Check-True $true ("progressPct 全程单调不减（{0} → {1}）" -f $pcts[0], $pcts[$pcts.Count - 1])
    } else {
        Write-Host ("    进度回退事件（{0} 次）：" -f $decreases.Count) -ForegroundColor DarkYellow
        foreach ($i in $decreases) {
            $a = $snapshots[$i - 1]; $b = $snapshots[$i]
            Write-Host ("      {0}% → {1}%（步 {2}/{3} → {4}/{5}，当前 {6} → {7}，已耗时 {8}ms → {9}ms）" -f `
                $a.progressPct, $b.progressPct, $a.finishedSteps, $a.totalSteps, $b.finishedSteps, $b.totalSteps, `
                $a.currentStepName, $b.currentStepName, $a.elapsedMs, $b.elapsedMs) -ForegroundColor DarkYellow
        }
        if (-not $hasCc) {
            Note-Skip ("progressPct 回退 {0} 次，但响应无 correctionCount 字段（agent-core 未重启？），无法判定是自主纠错重跑还是缺陷" -f $decreases.Count)
        } elseif ($cc -ge 1) {
            Check-True $true ("progressPct 回退 {0} 次，且 correctionCount={1} ≥ 1 —— 自主纠错重跑复位步骤的真实状态，非缺陷（前端需自行解释，见 docs/api/agent-core.md）" -f $decreases.Count, $cc)
        } else {
            Check-True $false ("progressPct 回退 {0} 次却 correctionCount=0：无任何机制可解释该回退，须排查" -f $decreases.Count)
        }
    }
    Check-True (@($pcts | Where-Object { $_ -lt 0 -or $_ -gt 100 }).Count -eq 0) "progressPct 落在 [0,100]"

    $pctOk = $true
    foreach ($s in $snapshots) {
        if ([int]$s.totalSteps -gt 0) {
            $exp = [Math]::Round(([int]$s.finishedSteps * 100.0 / [int]$s.totalSteps), 1)
            if ([Math]::Abs([double]$s.progressPct - $exp) -gt 0.05) { $pctOk = $false }
        }
    }
    Check-True $pctOk "progressPct 与 finished/total 一致（四舍五入 1 位小数）"

    $curOk = @($running | Where-Object { [string]::IsNullOrWhiteSpace($_.currentStepName) }).Count -eq 0
    Check-True $curOk "RUNNING 快照的 currentStepName 非空"
    $msgOk = @($running | Where-Object { $_.message -notmatch '^第 \d+/\d+ 步：『?.+』?$' }).Count -eq 0
    Check-True $msgOk "message 形如「第 N/M 步：步骤名」（前端可直接展示）"

    $sumOk = $true
    foreach ($s in $running) {
        if ([long]$s.estimatedTotalMs -ne ([long]$s.elapsedMs + [long]$s.estimatedRemainingMs)) { $sumOk = $false }
    }
    Check-True $sumOk "estimatedTotalMs = elapsedMs + estimatedRemainingMs"
    $posOk = @($running | Where-Object { [long]$_.estimatedRemainingMs -le 0 }).Count -eq 0
    Check-True $posOk "RUNNING 快照的 estimatedRemainingMs > 0"
    $srcOk = @($running | Where-Object { $_.estimateSource -notin @('HISTORY', 'DEFAULT') }).Count -eq 0
    Check-True $srcOk "estimateSource 只会是 HISTORY / DEFAULT（终态才是 FIXED）"
}

# ---------- 判据 C：ETA 与库内基线同口径 ----------
Write-Host "[6] 判据C：ETA 与库内历史基线同口径（关键判据）" -ForegroundColor Yellow
$row = Invoke-Sql "SELECT tenant_id, status, IFNULL(duration_ms,-1), finished_steps, total_steps FROM agent_task WHERE id = $taskId;"
$tf = @($row -split "`t")
$tenantId = $tf[0]

# 本任务各步骤的分桶键（复用与 XML 完全相同的分组口径）
$stepRows = Invoke-Sql "SELECT step_no, step_type, IFNULL(tool_name,''), IFNULL(agent_role,'') FROM agent_task_step WHERE task_id = $taskId AND deleted = 0 ORDER BY step_no;"
$steps = @()
foreach ($line in ($stepRows -split "`n")) {
    $c = @($line -split "`t")
    if ($c.Count -ge 4) {
        $steps += [pscustomobject]@{ stepNo = [int]$c[0]; stepType = $c[1]; toolName = $c[2]; agentRole = $c[3] }
    }
}
Check-True ($steps.Count -gt 0) ("取到本任务步骤定义 {0} 步" -f $steps.Count)

# 历史基线：与 AgentTaskStepMapper.avgDurationByStepKey 同口径（30 天 / SUCCESS / duration_ms 非空 / 按租户），
# 排除本任务自身（快照时刻本任务已完成的行不属于"当时的历史"）；FLOOR 对齐 Java 侧 avg.longValue() 的截断
$baseRows = Invoke-Sql ("SELECT step_type, IFNULL(tool_name,''), IFNULL(agent_role,''), FLOOR(AVG(duration_ms)), COUNT(*) " +
    "FROM agent_task_step WHERE tenant_id = $tenantId AND task_id <> $taskId AND deleted = 0 AND status = 'SUCCESS' " +
    "AND duration_ms IS NOT NULL AND created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY) " +
    "GROUP BY step_type, IFNULL(tool_name,''), IFNULL(agent_role,'');")
$baseline = @{}
foreach ($line in ($baseRows -split "`n")) {
    $c = @($line -split "`t")
    if ($c.Count -ge 5) { $baseline[("{0}|{1}|{2}" -f $c[0], $c[1], $c[2])] = @{ avgMs = [long]$c[3]; samples = [int]$c[4] } }
}
Write-Host ("    库内基线分组数 = {0}" -f $baseline.Count) -ForegroundColor DarkGray

$histSnaps = @($running | Where-Object { $_.estimateSource -eq 'HISTORY' })
if ($histSnaps.Count -eq 0) {
    Note-Skip "无 estimateSource=HISTORY 的快照（库内该租户缺同类历史样本时属预期退化，非产品缺陷）"
} else {
    # 每个快照都校验（判据不打折），但同一个进度档只打印一次明细——否则 400ms 轮询会把
    # 完全相同的期望块刷几十遍，反而淹没真正有变化的档位。
    $printed = @{}
    foreach ($s in $histSnaps) {
        $pending = @($steps | Where-Object { $_.stepNo -gt [int]$s.finishedSteps })
        $expRemaining = 0L; $expSamples = 0; $allHist = ($pending.Count -gt 0); $detail = @()
        foreach ($st in $pending) {
            $k = "{0}|{1}|{2}" -f $st.stepType, $st.toolName, $st.agentRole
            if ($baseline.ContainsKey($k)) {
                $expRemaining += [long]$baseline[$k].avgMs
                $expSamples += [int]$baseline[$k].samples
                $detail += ("{0}={1}ms×{2}" -f $k, $baseline[$k].avgMs, $baseline[$k].samples)
            } else {
                $allHist = $false
                $d = if ($st.stepType -eq 'LLM') { $DEFAULT_LLM_MS } else { $DEFAULT_TOOL_MS }
                $expRemaining += [long]$d
                $detail += ("{0}=缺省{1}ms" -f $k, $d)
            }
        }
        $expSource = if ($allHist -and $pending.Count -gt 0) { 'HISTORY' } else { 'DEFAULT' }
        $tol = [Math]::Max(300, [Math]::Round($expRemaining * 0.05))
        $diff = [Math]::Abs([long]$s.estimatedRemainingMs - $expRemaining)
        $tier = "{0}|{1}|{2}" -f $s.progressPct, $s.finishedSteps, $s.samples
        if (-not $printed.ContainsKey($tier)) {
            $printed[$tier] = $true
            Write-Host ("    pct={0}% 剩余步骤={1} 期望={2}ms(样本{3}) 实际={4}ms(样本{5}) 容差=±{6}ms" -f `
                $s.progressPct, $pending.Count, $expRemaining, $expSamples, $s.estimatedRemainingMs, $s.samples, $tol) -ForegroundColor DarkGray
            Write-Host ("      期望来源={0}，逐键：{1}" -f $expSource, ($detail -join ' + ')) -ForegroundColor DarkGray
        }
        Check-True ($s.estimateSource -eq $expSource) ("pct={0}% 步={1}/{2} 快照 estimateSource={3} 与库内基线可用性一致（期望 {4}）" -f $s.progressPct, $s.finishedSteps, $s.totalSteps, $s.estimateSource, $expSource)
        Check-True ([int]$s.samples -eq $expSamples) ("pct={0}% 步={1}/{2} samples={3} 与库内样本数逐键合计一致（期望 {4}）" -f $s.progressPct, $s.finishedSteps, $s.totalSteps, $s.samples, $expSamples)
        Check-True ($diff -le $tol) ("pct={0}% 步={1}/{2} estimatedRemainingMs={3}ms 与库内逐键合计 {4}ms 一致（差 {5}ms ≤ ±{6}ms）" -f `
            $s.progressPct, $s.finishedSteps, $s.totalSteps, $s.estimatedRemainingMs, $expRemaining, $diff, $tol)
    }
    Write-Host ("    已逐个核对 {0} 个 HISTORY 快照（去重后 {1} 个进度档）" -f $histSnaps.Count, $printed.Count) -ForegroundColor DarkGray
}

# ---------- 判据 D：终态定格 + 与库内实际耗时一致 ----------
Write-Host "[7] 判据D：终态定格与落库耗时一致" -ForegroundColor Yellow
$final = $snapshots[$snapshots.Count - 1]
$dbStatus = $tf[1]; $dbDuration = [long]$tf[2]
if ($final.status -in @('PENDING', 'RUNNING')) {
    Check-True $false ("末次快照仍处于 {0}（{1} 秒内未到终态；若流水线确实在跑可加大 -TimeoutSeconds）" -f $final.status, $TimeoutSeconds)
} else {
    Check-True $true ("已到终态 status={0}（{1}）" -f $final.status, $final.statusText)
    Check-True ([double]$final.progressPct -eq 100.0) ("终态 progressPct=100（实际 {0}）" -f $final.progressPct)
    Check-True ([int]$final.finishedSteps -eq [int]$final.totalSteps) ("终态 finishedSteps=totalSteps（{0}/{1}）" -f $final.finishedSteps, $final.totalSteps)
    Check-True ([long]$final.estimatedRemainingMs -eq 0) "终态不再估算（estimatedRemainingMs=0）"
    Check-True ($final.estimateSource -eq 'FIXED') ("终态 estimateSource=FIXED（实际 {0}）" -f $final.estimateSource)
    Check-True ([string]::IsNullOrWhiteSpace($final.currentStepName)) "终态无「当前步骤」"
    Check-True ([long]$final.elapsedMs -eq $dbDuration) ("终态 elapsedMs={0}ms 与库内 agent_task.duration_ms={1}ms 一致" -f $final.elapsedMs, $dbDuration)
    Check-True ($final.message -like '已完成（*') ("终态文案可读（{0}）" -f $final.message)
    Check-True ($dbStatus -eq $final.status) ("端点 status 与库内一致（{0}）" -f $dbStatus)
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} SKIP={2} ===" -f $script:pass, $script:fail, $script:skip) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("复核用 taskId={0}" -f $taskId) -ForegroundColor DarkGray
Write-Host ("  库内核对: SELECT step_type, IFNULL(tool_name,''), IFNULL(agent_role,''), FLOOR(AVG(duration_ms)), COUNT(*) FROM {0}.agent_task_step WHERE status='SUCCESS' AND duration_ms IS NOT NULL GROUP BY 1,2,3;" -f $Database) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
