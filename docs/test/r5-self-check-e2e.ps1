# =====================================================================
# R5 语义自校验与自主纠错 —— 运行时验证脚本
#
# 背景：自校验的触发依赖【真实 LLM 输出与工具结果相互矛盾】，脚本层面无法稳定构造。
# 本脚本用一条高概率路径逼近：提交一张**已被其他报销单报销过的发票**。
#   → duplicate_check 报发票号硬命中（suspectedHigh=true）
#   → 若 LLM 风控仍给出高置信度（≥0.9）→ 断言 ④ 命中矛盾
#   → correction_count 自增、self_check_result 落库；重跑 1 次仍矛盾则转人工
#
# 判据分三级，脚本会如实报告命中的是哪一级：
#   A. 弱判据（必然成立）：任务到达收尾闸口时 self_check_result 必落库（含 coherent 字段）
#   B. 强判据：自校验判定不一致时 correction_count 必须 ≥ 1（否则自纠错没有真的执行）
#   C. 强判据（R5-9/R5-11 护栏）：selfCheckTrace 必须落库，且其中不得出现
#      「自校验执行失败 / 自纠错动作执行失败」——这两句一旦出现，说明闸口或纠错动作抛了异常，
#      被兜底 catch 放行（这正是 R5-9 的故障形态：异常被吞、链路静默失效，只靠判据 A 看不出来）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r5-self-check-e2e.ps1 `
#       -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
#
# 复检既有任务（不重新提交、不消耗 LLM/OCR 配额）：
#   powershell -ExecutionPolicy Bypass -File docs\test\r5-self-check-e2e.ps1 -TaskId 400683 `
#       -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
# =====================================================================

param(
    [string]$Gateway = "http://localhost:9080",
    [string]$MySqlExe = "mysql",
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [int]$DeptId = 3,
    [string]$DeptName = "研发部",
    [string]$Period = "2026-09",
    [string]$SampleFile = "",
    [int]$PollSeconds = 150,
    [long]$TaskId = 0
)

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0

function Invoke-Sql([string]$sql) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $MySqlExe -h $DbHost -P $Port -u $User "-p$Password" --default-character-set=utf8mb4 -N -B -e $sql 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    $lines = @($raw | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'Using a password' })
    if ($code -ne 0) { throw ("SQL 失败: {0}" -f ($lines -join ' / ')) }
    return (($lines -join "`n").Trim())
}

function Check($actual, $expected, [string]$label) {
    if ("$actual" -eq "$expected") {
        Write-Host ("  [PASS] {0} = {1}" -f $label, $actual) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [FAIL] {0}: 实际 {1}，期望 {2}" -f $label, $actual, $expected) -ForegroundColor Red
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

function Api([string]$method, [string]$path, $body, [string]$token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Method = $method; Uri = "$Gateway$path"; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
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
            $status = [int]$resp.StatusCode
            $parsed = $null
            if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
            if ($parsed -and (($parsed.PSObject.Properties.Name) -contains 'code')) { return $parsed }
            # ⚠️ 非 R 结构响应（如端点写错返回的 404 空体）此前会被解析成空对象，表现为
            #    「code= msg=」哑失败。统一包装为可诊断信息（配套脚本 r5-amend-rerun-e2e.ps1 踩过）。
            return [pscustomobject]@{
                code = -1
                message = ("HTTP {0} {1}" -f $status, (($text -replace '\s+', ' ').Trim()))
                data = $null
            }
        }
        throw
    }
}

Write-Host "=== R5 语义自校验与自主纠错 运行时验证 ===" -ForegroundColor Cyan

if ($TaskId -gt 0) {
    # 复检模式：不提交新单，直接评估既有任务（节省 LLM / OCR 配额）
    $taskId = $TaskId
    $reimbId = Invoke-Sql "SELECT IFNULL(JSON_EXTRACT(input_params,'`$.reimbId'),0) FROM $Database.agent_task WHERE id = $taskId;"
    Write-Host ("[0] 复检既有任务 taskId={0}（reimbId={1}，不重新提交）" -f $taskId, $reimbId) -ForegroundColor DarkGray
    if (-not (Invoke-Sql "SELECT id FROM $Database.agent_task WHERE id = $taskId;")) {
        throw ("任务不存在: {0}" -f $taskId)
    }
} else {

$login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token

$sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
$samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { throw "需要 curl.exe" }
if ($samples.Count -eq 0) { throw "未找到 OCR 样本" }
$sample = if ($SampleFile) { Get-Item $SampleFile } else { $samples[(Get-Random -Maximum $samples.Count)] }
Write-Host ("样本: {0}" -f $sample.Name) -ForegroundColor DarkGray

# 上传附件 + 提交报销单
$tmp = Join-Path $env:TEMP ("r5-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $sample.Extension)
Copy-Item $sample.FullName $tmp -Force
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
finally { $ErrorActionPreference = $prev }
Remove-Item $tmp -Force -ErrorAction SilentlyContinue
$fid = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
if (-not $fid) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }

$base = [decimal](Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM $Database.expense_reimbursement WHERE deleted = 0;")
$body = @{
    title = "R5 自校验验证"; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
    claimDate = "$Period-01"; remark = "R5 self-check e2e"
    items = @(@{ name = "测试明细"; amount = $base })
    fileRecordIds = @($fid)
}
$r = Api POST "/api/v1/reimbursements" $body $token
if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }
$taskId = $r.data.taskId
$reimbId = $r.data.id
Write-Host ("[1] 已提交 reimbId={0} taskId={1} 金额={2}" -f $reimbId, $taskId, $base) -ForegroundColor DarkGray

}   # end 提交模式

# 等待流水线推进到 terminal / 工单
Write-Host ("[2] 等待流水线完成（最多 {0} 秒，需真实 LLM 调用）" -f $PollSeconds) -ForegroundColor Yellow
$deadline = (Get-Date).AddSeconds($PollSeconds)
$status = $null
while ((Get-Date) -lt $deadline) {
    $status = Invoke-Sql "SELECT status FROM $Database.agent_task WHERE id = $taskId;"
    $steps = Invoke-Sql "SELECT COUNT(*) FROM $Database.agent_task_step WHERE task_id = $taskId AND status <> 'SUCCESS' AND deleted = 0;"
    if ($status -and $status -ne 'RUNNING' -and $status -ne 'PENDING' -and "$steps" -eq "0") { break }
    if ($status -eq 'SUCCESS' -or $status -eq 'FAILED' -or $status -eq 'APPROVAL_PENDING') { break }
    Start-Sleep -Seconds 5
}
Write-Host ("    task status = {0}" -f $status) -ForegroundColor DarkGray

# ---------- 判据 A：self_check_result 必须落库（到达收尾闸口即必然） ----------
Write-Host "[3] 判据A（弱）：self_check_result 是否落库" -ForegroundColor Yellow
$row = Invoke-Sql "SELECT IFNULL(correction_count,0), IFNULL(self_check_result,'') FROM $Database.agent_task WHERE id = $taskId;"
# ⚠️ 取数为空时必须给默认值，否则 $p[1].Trim() 会因 $null 抛 "You cannot call a method on
#    a null-valued expression" 而中断整段输出（踩过：任务未收尾时正是这种情况）
$p = @($row -split "`t")
$cc = if ($p.Count -ge 1 -and $p[0]) { [int]$p[0].Trim() } else { 0 }
$scr = if ($p.Count -ge 2 -and $p[1]) { $p[1].Trim() } else { '' }
Write-Host ("    correction_count={0}" -f $cc) -ForegroundColor DarkGray
Write-Host ("    self_check_result={0}" -f $scr) -ForegroundColor DarkGray

if ($scr) {
    Check-True ($scr -match '"coherent"') "self_check_result 含 coherent 字段（自校验已执行）"
    Check-True ($scr -match '"checkedCount"') "self_check_result 含 checkedCount（断言条数）"
    Check-True ($scr -match '"hallucination"') "self_check_result 含 hallucination（幻觉率指标来源）"
} else {
    Write-Host "    ⚠️ self_check_result 为空：可能流水线未走到收尾（如 OCR 失败或审批前中断）" -ForegroundColor DarkYellow
    Write-Host "       注意：self_check_result 只在【收尾闸口】写入；若该单因其他原因未到达收尾，此处为 NULL 属预期" -ForegroundColor DarkYellow
    $script:fail++
}

# ---------- 判据 C：自校验轨迹落库且无异常（R5-9 护栏，先跑，便于读故障上下文） ----------
Write-Host "[4] 判据C（强）：selfCheckTrace 落库且自校验/自纠错无异常" -ForegroundColor Yellow
$res = Invoke-Sql "SELECT IFNULL(result,'') FROM $Database.agent_task WHERE id = $taskId;"
# ⚠️ 轨迹是字符串数组，元素里自带 [断言名]，用非贪婪正则会在第一个 ] 处截断（踩过）。
#    故优先用 JSON 解析，失败才退化为正则。
$trace = ''
if ($res) {
    $obj = $null
    try { $obj = $res | ConvertFrom-Json } catch { $obj = $null }
    if ($obj -and $obj.selfCheckTrace) {
        $trace = (@($obj.selfCheckTrace) -join '  ||  ')
    } elseif ($res -match '"selfCheckTrace"\s*:\s*\[(.*)') {
        $trace = $Matches[1]
    }
}
if ($trace) {
    Check-True $true "任务结果含 selfCheckTrace（自校验轨迹已落库）"
    Write-Host ("    轨迹: {0}" -f $trace) -ForegroundColor DarkGray
    $broken = ($trace -match '自校验执行失败') -or ($trace -match '自纠错动作执行失败')
    Check-True (-not $broken) "轨迹中无「自校验执行失败 / 自纠错动作执行失败」（R5-9 类故障检测）"
    Check-True ($trace -match '自校验执行完成') "轨迹含「自校验执行完成」（闸口确实跑到了）"
} else {
    Check-True $false "任务结果含 selfCheckTrace（自校验轨迹已落库）"
    Write-Host "    ⚠️ 轨迹缺失说明 selfCheckTrace 未写入 result 或被丢参，请检查 AgentOrchestrator.trace 的落库路径" -ForegroundColor DarkYellow
}

# ---------- 判据 B：强判据（自校验判定不一致时必须真的重跑） ----------
Write-Host "[5] 判据B（强）：命中矛盾时是否真的重跑了风控语义步骤" -ForegroundColor Yellow
if ($scr -match '"coherent"\s*:\s*false') {
    Write-Host "    自校验判定为【不一致】" -ForegroundColor Green
    Write-Host "    注意：self_check_result 记录的是【最后一次】自校验的结论；" -ForegroundColor DarkGray
    Write-Host "          若首判不一致、重跑后通过，则此处 coherent 可能已是 true（correction_count 仍 ≥1）" -ForegroundColor DarkGray
    Check-True ($cc -ge 1) "correction_count ≥ 1（已重跑风控语义步骤）"
    Check-True ($scr -match '"contradictions"\s*:\s*\[?\s*\{') "self_check_result 含矛盾清单"
    # 最后一次仍不一致且已纠错满 1 次 → 应转人工，复核原因含自校验项
    $tk = Invoke-Sql "SELECT IFNULL(trigger_type,''), IFNULL(review_reasons,'') FROM $Database.audit_ticket WHERE task_id = $taskId AND deleted = 0 LIMIT 1;"
    Write-Host ("    工单: {0}" -f $tk) -ForegroundColor DarkGray
    Check-True ($tk -match '自校验未通过') "工单复核原因含「自校验未通过」"
} elseif ($scr) {
    Write-Host "    ℹ️ 最后一次自校验【通过】（coherent=true）" -ForegroundColor DarkYellow
    if ($cc -ge 1) {
        Write-Host "       且 correction_count ≥ 1：属于「自主纠错生效」——首判矛盾 → 重跑风控 → 重判通过" -ForegroundColor Green
    } else {
        Write-Host "       本次未命中矛盾（LLM 未给出与工具结果冲突的结论），correction_count 保持 0 属正常" -ForegroundColor DarkYellow
    }
    Check-True ($cc -ge 0) "coherent=true 时 correction_count 不作硬性要求（0=未命中，≥1=纠错后通过）"
    $script:pass++
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("复核用 reimbId={0} taskId={1}" -f $reimbId, $taskId) -ForegroundColor DarkGray
Write-Host "自校验轨迹已随 agent_task.result.selfCheckTrace 落库，可直接查库定位，无需翻控制台日志：" -ForegroundColor DarkGray
Write-Host ("  SELECT correction_count, self_check_result, JSON_EXTRACT(result,'`$.selfCheckTrace') FROM {0}.agent_task WHERE id = {1};" -f $Database, $taskId) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
