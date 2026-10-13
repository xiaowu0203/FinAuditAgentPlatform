# =====================================================================
# R6-1 GENERIC 通用分析任务 运行时验证脚本
#
# 背景：GENERIC 此前是「遗留调试通道」——收尾直接 markSuccess，
#       既不过语义自校验，也不因 LLM 给出 REJECT/存疑结论而建审批工单，
#       于是"分析结论不可信"时任务照样显示成功、风险无人接手。
#       R6-1 让 GENERIC 与报销走同一套收尾闸口：自校验 → 结果分支 → 必要时建工单。
#
# 判据：
#   A（必过）任务到达终态（SUCCESS 或 APPROVAL_PENDING），且无 FAILED 步骤
#   B（必过）自校验确实执行：self_check_result 非空
#   C（必过）自校验轨迹落库且不含「自校验执行失败 / 自纠错动作执行失败」
#   D（必过）结果分支与工单一致：NEED_REVIEW ⇒ 有工单；AUTO_PASS ⇒ 无工单
#
# 两个场景（-Scenario）：
#   autoPass   ：常规分析数据，LLM 多半给 APPROVE → 验证 AUTO_PASS 分支不建工单
#   needReview ：数据里带明显异常（同一票据号出现两次 + 金额互斥），逼出非 APPROVE 结论
#                → 验证 NEED_REVIEW 分支确实建审批工单（R6-1 的核心新行为）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r6-generic-task-e2e.ps1 `
#       -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
#   ... -Scenario needReview      # 高风险场景
# =====================================================================

param(
    [string]$Gateway = "http://localhost:9080",
    [string]$MySqlExe = "mysql",
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [int]$PollSeconds = 180,
    [long]$TaskId = 0,
    [ValidateSet("autoPass", "needReview")]
    [string]$Scenario = "autoPass"
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
            return [pscustomobject]@{ code = -1; message = ("HTTP {0} {1}" -f $status, (($text -replace '\s+', ' ').Trim())); data = $null }
        }
        throw
    }
}

Write-Host "=== R6-1 GENERIC 通用分析任务 运行时验证 ===" -ForegroundColor Cyan

if ($TaskId -gt 0) {
    $taskId = $TaskId
    Write-Host ("[0] 复检既有任务 taskId={0}（不重新提交）" -f $taskId) -ForegroundColor DarkGray
} else {
    $login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
    if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
    $token = $login.data.token

    # 通用分析任务：任务入参刻意给一组需要判断的数据（非财务场景，GENERIC 工具目录为空 → 纯 LLM 分析）
    if ($Scenario -eq 'needReview') {
        # 高风险场景：同一票据号出现两次且金额互斥 → 期望 LLM 给出 REJECT/NEED_INFO（非 APPROVE）
        $body = @{
            title = "R6 GENERIC 高风险场景验证"
            taskType = "GENERIC"
            inputParams = @{
                question = "下列付款申请是否存在重复付款或金额异常？请给出明确结论（通过/驳回/需补充材料）与依据。"
                period = "2026-09"
                payments = @(
                    @{ payee = "东莞京东旭弘贸易有限公司"; invoiceNo = "044002311111-07632553"; amount = 7741.75; date = "2026-09-01" },
                    @{ payee = "东莞京东旭弘贸易有限公司"; invoiceNo = "044002311111-07632553"; amount = 7741.75; date = "2026-09-03" },
                    @{ payee = "深圳某某科技"; invoiceNo = "144032509110-27555782"; amount = 300.00; date = "2026-09-05" }
                )
                note = "第 1、2 条为同一张票据号的两次付款申请"
            }
        }
    } else {
        $body = @{
            title = "R6 GENERIC 通用分析验证"
            taskType = "GENERIC"
            inputParams = @{
                question = "下列三个部门的月度费用环比变化是否异常？请给出结论与依据。"
                period = "2026-09"
                data = @(
                    @{ dept = "研发部"; lastMonth = 120000; thisMonth = 138000 },
                    @{ dept = "市场部"; lastMonth = 90000; thisMonth = 260000 },
                    @{ dept = "财务部"; lastMonth = 45000; thisMonth = 44000 }
                )
            }
        }
    }
    $r = Api POST "/api/v1/tasks" $body $token
    if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }
    $taskId = $r.data.id
    Write-Host ("[1] 已提交 GENERIC 任务（场景 {0}）taskId={1} taskNo={2} status={3}" -f $Scenario, $taskId, $r.data.taskNo, $r.data.status) -ForegroundColor DarkGray
}

# 等待流水线到达终态
Write-Host ("[2] 等待流水线完成（最多 {0} 秒，需真实 LLM 调用）" -f $PollSeconds) -ForegroundColor Yellow
$deadline = (Get-Date).AddSeconds($PollSeconds)
$status = $null
while ((Get-Date) -lt $deadline) {
    $status = Invoke-Sql "SELECT status FROM agent_task WHERE id = $taskId;"
    $pending = Invoke-Sql "SELECT COUNT(*) FROM agent_task_step WHERE task_id = $taskId AND deleted = 0 AND status <> 'SUCCESS';"
    if ("$pending" -eq "0" -and $status -ne 'RUNNING' -and $status -ne 'PENDING') { break }
    if ($status -eq 'SUCCESS' -or $status -eq 'FAILED' -or $status -eq 'APPROVAL_PENDING') { break }
    Start-Sleep -Seconds 5
}
$taskType = Invoke-Sql "SELECT task_type FROM agent_task WHERE id = $taskId;"
Write-Host ("    task_type={0} status={1}" -f $taskType, $status) -ForegroundColor DarkGray

# ---------- 判据 A：终态且无失败步骤 ----------
Write-Host "[3] 判据A：到达终态" -ForegroundColor Yellow
Check-True ($taskType -eq 'GENERIC') "任务类型为 GENERIC（实际 $taskType）"
$failedSteps = Invoke-Sql "SELECT COUNT(*) FROM agent_task_step WHERE task_id = $taskId AND deleted = 0 AND status = 'FAILED';"
Check-True ("$failedSteps" -eq "0") "无 FAILED 步骤（实际 $failedSteps）"
Check-True ($status -eq 'SUCCESS' -or $status -eq 'APPROVAL_PENDING') "到达终态 SUCCESS/APPROVAL_PENDING（实际 $status）"

# ---------- 判据 B/C：自校验执行 + 轨迹落库 ----------
Write-Host "[4] 判据B/C：自校验执行与轨迹落库" -ForegroundColor Yellow
$scr = Invoke-Sql "SELECT IFNULL(self_check_result,'') FROM agent_task WHERE id = $taskId;"
Check-True ($scr -match '"coherent"') "self_check_result 已落库（GENERIC 同样过自校验闸口）"

$res = Invoke-Sql "SELECT IFNULL(result,'') FROM agent_task WHERE id = $taskId;"
$trace = ''
$branch = ''
if ($res) {
    $obj = $null
    try { $obj = $res | ConvertFrom-Json } catch { $obj = $null }
    if ($obj) {
        if ($obj.selfCheckTrace) { $trace = (@($obj.selfCheckTrace) -join '  ||  ') }
        $branch = "$($obj.flowBranch)"
    }
}
Check-True ([bool]$trace) "result.selfCheckTrace 已落库"
if ($trace) { Write-Host ("    轨迹: {0}" -f $trace) -ForegroundColor DarkGray }
Check-True (-not (($trace -match '自校验执行失败') -or ($trace -match '自纠错动作执行失败'))) "轨迹无自校验/自纠错异常"
Check-True ($branch -eq 'AUTO_PASS' -or $branch -eq 'NEED_REVIEW') "结果分支已写入（flowBranch=$branch）"

# ---------- 判据 D：分支与工单一致 ----------
Write-Host "[5] 判据D：结果分支与审批工单一致" -ForegroundColor Yellow
$ticketCount = Invoke-Sql "SELECT COUNT(*) FROM audit_ticket WHERE task_id = $taskId AND deleted = 0;"
$ticketInfo = Invoke-Sql "SELECT IFNULL(trigger_type,''), IFNULL(status,'') FROM audit_ticket WHERE task_id = $taskId AND deleted = 0 LIMIT 1;"
Write-Host ("    flowBranch={0} ticketCount={1} ticket={2}" -f $branch, $ticketCount, $ticketInfo) -ForegroundColor DarkGray

if ($branch -eq 'NEED_REVIEW') {
    Check-True ([int]$ticketCount -ge 1) "NEED_REVIEW ⇒ 已生成审批工单（GENERIC 高风险同样交人工）"
    Check-True ($status -eq 'APPROVAL_PENDING') "NEED_REVIEW ⇒ 任务置 APPROVAL_PENDING（实际 $status）"
} elseif ($branch -eq 'AUTO_PASS') {
    Check-True ([int]$ticketCount -eq 0) "AUTO_PASS ⇒ 不建工单"
    Check-True ($status -eq 'SUCCESS') "AUTO_PASS ⇒ 任务 SUCCESS（实际 $status）"
} else {
    Check-True $false "flowBranch 应为 AUTO_PASS 或 NEED_REVIEW（实际 $branch）"
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("复核用 taskId={0}" -f $taskId) -ForegroundColor DarkGray
Write-Host ("  SELECT status, self_check_result, JSON_EXTRACT(result,'`$.flowBranch'), JSON_EXTRACT(result,'`$.selfCheckTrace') FROM {0}.agent_task WHERE id = {1};" -f $Database, $taskId) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
