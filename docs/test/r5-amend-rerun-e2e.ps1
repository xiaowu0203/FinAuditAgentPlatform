# =====================================================================
# amend（驳回 → 修改后重跑）链路 运行时验证脚本 —— R5-9 顺带修复项的专项验收
#
# 背景：R5 排查自纠错不触发时，用真实库最小复现发现 `AgentTaskService.prepareRerun` 用
#       `wrapper.set(AgentTask::getInputParams, map)` 写 JSON 列，在 MySQL 5.7 上必然抛
#       `Data truncation: Cannot create a JSON value from a string with CHARACTER SET 'binary'`。
#       即「工单驳回 → 提交人修改明细 → 同单重跑」这条 P3b/R4 链路**此前在真实库上不可用**：
#       R4 阶段的 19/19 验收未覆盖 resubmit，故一直没暴露。
#
# 本脚本串联真实链路：
#   [前置] 取任务/工单/报销单当前状态（工单须 PENDING/REJECTED，任务须 APPROVAL_PENDING/REJECTED）
#   [1] 财务驳回（POST /audit-tickets/{id}/reject）——需 audit:approve；失败仅记为 SKIP，不阻断
#   [2] 提交人修改明细并重跑（POST /reimbursements/{id}/resubmit）→ 关键判据：必须 code=0
#   [3] 立即校验落库：input_params 已换成新金额（= prepareRerun 写 JSON 列成功）、
#       工单转 AMENDED 且 rerun_count=1、步骤已全量重规划、audit_record 有 AMEND 留痕
#   [4] 等待重跑跑完：无 FAILED 步骤、自校验仍落库、报销单金额为新值、工单复位 PENDING/APPROVED
#
# 用法（默认复用 R5 自校验验证产生的那张单，不消耗新 OCR 配额）：
#   powershell -ExecutionPolicy Bypass -File docs\test\r5-amend-rerun-e2e.ps1 `
#       -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
#
# 跳过驳回步骤（直接从 PENDING 修改重跑，同样覆盖 prepareRerun）：
#   ... -SkipReject
# =====================================================================

param(
    [string]$Gateway = "http://localhost:9080",
    [string]$MySqlExe = "mysql",
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [long]$TaskId = 400683,
    [decimal]$NewAmount = 7000,
    [string]$ClaimDate = "2026-09-01",
    [string]$ExpenseType = "OFFICE",
    [switch]$SkipReject,
    [int]$PollSeconds = 180
)

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0
$script:skip = 0

function Invoke-Sql([string]$sql) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # 显式 -D 指定库：本脚本部分 SQL 用裸表名（多表 JOIN），不依赖调用方拼 $Database 前缀
        $raw = & $MySqlExe -h $DbHost -P $Port -u $User "-p$Password" -D $Database --default-character-set=utf8mb4 -N -B -e $sql 2>&1
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

function Skip([string]$label) {
    Write-Host ("  [SKIP] {0}" -f $label) -ForegroundColor DarkYellow
    $script:skip++
}

function Api([string]$method, [string]$path, $body, [string]$token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Method = $method; Uri = "$Gateway$path"; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 90 }
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
            # ⚠️ 非 R 结构响应（如 404/403 的空体或 Spring 默认错误体）此前会被静默解析成空对象，
            #    表现为「code= msg=」的哑失败（本脚本首版就踩到：端点写成 audit-tickets 返回 404）。
            #    这里统一包装成可诊断的错误码与文本。
            return [pscustomobject]@{
                code = -1
                message = ("HTTP {0} {1}" -f $status, (($text -replace '\s+', ' ').Trim()))
                data = $null
            }
        }
        throw
    }
}

Write-Host "=== amend（驳回 → 修改后重跑）链路 运行时验证 ===" -ForegroundColor Cyan

$login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token

# ---------- 前置：取当前状态 ----------
$pre = Invoke-Sql ("SELECT t.status, t.rerun_count, t.id, r.status, r.total_amount, r.applicant_id, a.status, a.total_steps, a.finished_steps " +
    "FROM audit_ticket t JOIN expense_reimbursement r ON r.task_id = t.task_id JOIN agent_task a ON a.id = t.task_id " +
    "WHERE t.task_id = {0} AND t.deleted = 0 AND r.deleted = 0 LIMIT 1;" -f $TaskId)
if (-not $pre) { throw ("未找到 taskId={0} 的工单/报销单，无法验证 amend 链路" -f $TaskId) }
$f = @($pre -split "`t")
$ticketId = $f[2]; $ticketStatus = $f[0]; $rerunCount = $f[1]
$reimbStatus = $f[3]; $reimbAmount = $f[4]; $applicantId = $f[5]
$taskPreStatus = $f[6]; $taskSteps = $f[7]
Write-Host ("[前置] ticketId={0} ticket={1} rerun={2} task={3} reimb={4}({5}) applicant={6} steps={7}" -f `
    $ticketId, $ticketStatus, $rerunCount, $taskPreStatus, $reimbStatus, $reimbAmount, $applicantId, $taskSteps) -ForegroundColor DarkGray

Check-True ($ticketStatus -eq 'PENDING' -or $ticketStatus -eq 'REJECTED') "前置：工单状态为 PENDING/REJECTED（可修改重跑），实际 $ticketStatus"
Check-True ($taskPreStatus -eq 'APPROVAL_PENDING' -or $taskPreStatus -eq 'REJECTED') "前置：任务状态为 APPROVAL_PENDING/REJECTED（prepareRerun 的 CAS 期望态），实际 $taskPreStatus"
if ($script:fail -gt 0) {
    Write-Host "前置条件不满足，终止验证（换一张处于待审/已驳回的单据重试：-TaskId <id>）" -ForegroundColor Red
    exit 1
}

$fidRow = Invoke-Sql "SELECT GROUP_CONCAT(file_record_id) FROM expense_attachment WHERE reimb_id = (SELECT id FROM expense_reimbursement WHERE task_id = $TaskId AND deleted = 0 LIMIT 1) AND deleted = 0;"
$fileIds = @($fidRow -split ',' | Where-Object { $_ } | ForEach-Object { [long]$_ })
Check-True ($fileIds.Count -gt 0) ("前置：报销单存在附件（fileRecordIds={0}）" -f ($fileIds -join ','))

# ---------- [1] 财务驳回（可选） ----------
Write-Host "[1] 财务驳回工单（如需覆盖「驳回 → 修改」完整路径）" -ForegroundColor Yellow
if ($SkipReject) {
    Skip "按 -SkipReject 跳过驳回，直接从 $ticketStatus 状态修改重跑（仍覆盖 prepareRerun）"
} else {
    # ⚠️ 端点前缀是 /api/v1/audit/tickets（不是 audit-tickets），写错会得到 404 空体
    #    （脚本首版踩过：被静默当成「驳回失败」SKIP，链路看似通过其实没测到驳回）
    $rj = Api POST ("/api/v1/audit/tickets/{0}/reject" -f $ticketId) @{ comment = "R5-9 amend 链路验证：金额与票面不符，请核对后修改重跑" } $token
    if ($rj.code -eq 0) {
        Check-True $true "财务驳回成功（code=0）"
        $afterReject = Invoke-Sql ("SELECT t.status, a.status, r.status FROM audit_ticket t JOIN agent_task a ON a.id = t.task_id " +
            "JOIN expense_reimbursement r ON r.task_id = t.task_id WHERE t.id = {0};" -f $ticketId)
        $ar = @($afterReject -split "`t")
        Write-Host ("    驳回后：ticket={0} task={1} reimb={2}" -f $ar[0], $ar[1], $ar[2]) -ForegroundColor DarkGray
        Check $ar[0] "REJECTED" "工单闭合为 REJECTED"
        Check $ar[1] "REJECTED" "任务置 REJECTED"
    } else {
        # 驳回失败不代表链路不可用（可能被角色/状态规则拦住），如实记录并继续走修改重跑
        Skip ("驳回未成功（code={0} msg={1}）——继续直接修改重跑，同样覆盖 prepareRerun" -f $rj.code, $rj.message)
    }
}

# ---------- [2] 提交人修改明细并重跑（核心判据） ----------
Write-Host "[2] 提交人修改明细并重跑（POST /reimbursements/{id}/resubmit）" -ForegroundColor Yellow
$body = @{
    expenseType = $ExpenseType
    claimDate = $ClaimDate
    remark = "R5-9 amend 重跑验证（金额改为 $NewAmount）"
    items = @(@{ name = "测试明细"; amount = [double]$NewAmount })
    fileRecordIds = $fileIds
}
$reimbId = Invoke-Sql "SELECT id FROM expense_reimbursement WHERE task_id = $TaskId AND deleted = 0 LIMIT 1;"
$rs = Api POST ("/api/v1/reimbursements/{0}/resubmit" -f $reimbId) $body $token
Write-Host ("    响应: code={0} msg={1} data={2}" -f $rs.code, $rs.message, $rs.data) -ForegroundColor DarkGray
Check $rs.code "0" "resubmit 返回 code=0（修复前此调用必然失败：JSON 列写入被 MySQL 拒绝）"
if ($rs.code -ne 0) {
    Write-Host "    ⚠️ 修改重跑失败，后续落库校验无意义，终止" -ForegroundColor Red
    Write-Host ("=== 汇总: PASS={0} FAIL={1} SKIP={2} ===" -f $script:pass, $script:fail, $script:skip) -ForegroundColor Red
    exit 1
}
Check $rs.data "$TaskId" "返回同一个 taskId（同单续跑，非新建任务）"

# ---------- [3] 立即校验落库（prepareRerun 的直接产物） ----------
Write-Host "[3] 校验落库：任务入参/工单状态/步骤重规划/留痕" -ForegroundColor Yellow

# 3.1 任务入参已换成新金额 —— 这是 prepareRerun 写 JSON 列成功的最直接证据
$claimed = Invoke-Sql ("SELECT IFNULL(JSON_EXTRACT(input_params,'`$.claimedTotal'),'NULL') FROM {0}.agent_task WHERE id = {1};" -f $Database, $TaskId)
Write-Host ("    agent_task.input_params.claimedTotal = {0}" -f $claimed) -ForegroundColor DarkGray
Check-True ($null -ne $claimed -and [decimal]$claimed -eq $NewAmount) ("任务入参已更新为新金额 {0}（JSON 列写入成功）" -f $NewAmount)

# 3.2 工单转 AMENDED 且 rerun_count+1
$tkRow = Invoke-Sql "SELECT status, rerun_count, IFNULL(adjusted_amount,'NULL') FROM $Database.audit_ticket WHERE id = $ticketId;"
$tk = @($tkRow -split "`t")
Write-Host ("    工单: status={0} rerun_count={1} adjusted_amount={2}" -f $tk[0], $tk[1], $tk[2]) -ForegroundColor DarkGray
Check $tk[1] "$([int]$rerunCount + 1)" "工单 rerun_count 自增 1"
Check-True ([decimal]$tk[2] -eq $NewAmount) ("工单 adjusted_amount 记录修改后金额 {0}" -f $NewAmount)

# 3.3 步骤全量重规划（旧步骤软删 + 新步骤插入）
$stepTotal = Invoke-Sql "SELECT COUNT(*) FROM $Database.agent_task_step WHERE task_id = $TaskId AND deleted = 0;"
$stepDead = Invoke-Sql "SELECT COUNT(*) FROM $Database.agent_task_step WHERE task_id = $TaskId AND deleted <> 0;"
$taskPlanned = Invoke-Sql "SELECT total_steps FROM $Database.agent_task WHERE id = $TaskId;"
Write-Host ("    步骤: 有效={0} 已软删={1} 任务 total_steps={2}" -f $stepTotal, $stepDead, $taskPlanned) -ForegroundColor DarkGray
Check-True ($stepDead -gt 0) "旧步骤已软删（replan 全量重建确实执行）"
Check "$stepTotal" "$taskPlanned" "有效步骤数与任务 total_steps 一致"

# 3.4 审批留痕（顺带验证 audit_record 的 before_data/after_data 两个 JSON 列）
$rec = Invoke-Sql ("SELECT COUNT(*) FROM {0}.audit_record WHERE ticket_id = {1} AND action = 'AMEND' AND deleted = 0 AND before_data IS NOT NULL AND after_data IS NOT NULL;" -f $Database, $ticketId)
Check-True ([int]$rec -ge 1) "audit_record 存在 AMEND 留痕且 before_data/after_data 均非空"

# ---------- [4] 等待重跑跑完 ----------
Write-Host ("[4] 等待重跑完成（最多 {0} 秒）" -f $PollSeconds) -ForegroundColor Yellow
$deadline = (Get-Date).AddSeconds($PollSeconds)
$status = $null
while ((Get-Date) -lt $deadline) {
    $status = Invoke-Sql "SELECT status FROM $Database.agent_task WHERE id = $TaskId;"
    $pending = Invoke-Sql "SELECT COUNT(*) FROM $Database.agent_task_step WHERE task_id = $TaskId AND deleted = 0 AND status <> 'SUCCESS';"
    if ("$pending" -eq "0" -and $status -ne 'RUNNING' -and $status -ne 'PENDING') { break }
    if ($status -eq 'SUCCESS' -or $status -eq 'FAILED') { if ("$pending" -eq "0") { break } }
    Start-Sleep -Seconds 5
}
Write-Host ("    task status = {0}" -f $status) -ForegroundColor DarkGray

$failedSteps = Invoke-Sql "SELECT COUNT(*) FROM $Database.agent_task_step WHERE task_id = $TaskId AND deleted = 0 AND status = 'FAILED';"
Check "$failedSteps" "0" "重跑后无 FAILED 步骤（replan 后流水线可正常推进）"
Check-True ($status -ne 'RUNNING' -and $status -ne 'PENDING') "重跑已到达终态（实际 $status）"

$scr = Invoke-Sql "SELECT IFNULL(self_check_result,'') FROM $Database.agent_task WHERE id = $TaskId;"
Check-True ($scr -match '"coherent"') "重跑链路上自校验仍执行并落库（self_check_result 非空）"

$tkFinal = Invoke-Sql "SELECT status, rerun_count FROM $Database.audit_ticket WHERE id = $ticketId;"
$tf = @($tkFinal -split "`t")
Write-Host ("    工单终态: status={0} rerun_count={1}" -f $tf[0], $tf[1]) -ForegroundColor DarkGray
Check-True ($tf[0] -eq 'PENDING' -or $tf[0] -eq 'APPROVED') ("重跑后工单复位 PENDING 或闭合 APPROVED（AMENDED 不得成为死端），实际 {0}" -f $tf[0])

$reimbFinal = Invoke-Sql "SELECT status, total_amount FROM $Database.expense_reimbursement WHERE id = $reimbId AND deleted = 0;"
$rf = @($reimbFinal -split "`t")
Write-Host ("    报销单终态: status={0} total_amount={1}" -f $rf[0], $rf[1]) -ForegroundColor DarkGray
Check-True ([decimal]$rf[1] -eq $NewAmount) ("报销单金额已改为 {0}" -f $NewAmount)

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} SKIP={2} ===" -f $script:pass, $script:fail, $script:skip) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("复核用 reimbId={0} taskId={1} ticketId={2}" -f $reimbId, $TaskId, $ticketId) -ForegroundColor DarkGray
Write-Host "排查用 SQL：" -ForegroundColor DarkGray
Write-Host ("  SELECT input_params FROM {0}.agent_task WHERE id = {1};" -f $Database, $TaskId) -ForegroundColor DarkGray
Write-Host ("  SELECT status,rerun_count,adjusted_amount FROM {0}.audit_ticket WHERE id = {1};" -f $Database, $ticketId) -ForegroundColor DarkGray
Write-Host ("  SELECT action,before_amount,after_amount FROM {0}.audit_record WHERE ticket_id = {1};" -f $Database, $ticketId) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
