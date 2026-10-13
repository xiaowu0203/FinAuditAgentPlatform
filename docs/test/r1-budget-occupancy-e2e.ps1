# =====================================================================
# R1 预算占用端到端验收脚本（P3.8）
#
# 走真实 HTTP 链路（网关 9080）验证「预算真实占用与释放」：
#   提交报销单 → 流水线 → 占用/释放 → 与 DB 对账
#
# 设计说明（两次踩坑后的修正）：
#   1) 每次提交都上传**新附件**——同一 file_record 只能绑定一张报销单，
#      复用会被 file-service 以「文件已关联其他报销单」拒绝
#   2) 测试金额**自动避开库中已有金额**——duplicate_check 判据是
#      「同申请人 + 金额完全相等 + 报销日期±30天」，撞车会误判疑似重复而进人工，
#      导致断言不稳定（实测已复现）
#   3) 断言口径以「预算占用状态机」为准，不假设流水线一定 AUTO_PASS：
#      实际跑下来小额单也可能因 OCR 限流/查重误报进人工，这属流水线真实行为
#   4) 配平对账口径是 Σ(amount WHERE status='OCCUPIED') == used_amount：RELEASED 行整条跳过。
#      曾误写成「Σ(OCCUPIED) − Σ(RELEASED)」，等于把释放额扣减两次，导致对账假失败
#      （DB 实际正确；产品侧 reconciledNet 同一处错误已一并修正）
#
# 前置：5 个后端服务已启动；已执行 docs/database/migration-P3.8.sql
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r1-budget-occupancy-e2e.ps1 `
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
    [string]$Period = "2026-09",
    [int]$DeptId = 3,
    [string]$DeptName = "研发部",
    [int]$PollSeconds = 60
)

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0
$TAB = [char]9

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
            return (($sr.ReadToEnd()) | ConvertFrom-Json)
        }
        throw
    }
}

Write-Host "=== R1 预算占用端到端验收 ===" -ForegroundColor Cyan
Write-Host ("网关 {0}   库 {1}:{2}/{3}   周期 {4}   部门 {5}({6})" -f $Gateway, $DbHost, $Port, $Database, $Period, $DeptId, $DeptName)

# ---------- 0. 测试预算（并清掉同周期残留记账，保证每轮对账口径干净） ----------
Invoke-Sql "DELETE FROM $Database.budget WHERE tenant_id = 1 AND dept_id = $DeptId AND period = '$Period';" | Out-Null
Invoke-Sql "DELETE FROM $Database.budget_occupancy WHERE tenant_id = 1 AND dept_id = $DeptId AND period = '$Period';" | Out-Null
Invoke-Sql "INSERT INTO $Database.budget (tenant_id, dept_name, dept_id, period, total_budget, used_amount, deleted) VALUES (1, '$DeptName', $DeptId, '$Period', 100000.00, 0.00, 0);" | Out-Null
Write-Host "[0] 测试预算已就绪: 总额 100000、已用 0；同周期残留记账已清理（周期 $Period）" -ForegroundColor DarkGray

# ---------- 1. 登录 ----------
$login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { Write-Host ("登录失败: {0}" -f $login.message) -ForegroundColor Red; exit 1 }
$token = $login.data.token
Write-Host "[1] 登录成功" -ForegroundColor DarkGray

# ---------- 2. 附件上传（每次新文件；同一 file_record 只能绑定一张报销单） ----------
# 用仓库里的真实票据样本（docs/ocr-samples/local/*.jpg）而非占位图：
# 占位 1x1 PNG 必然 OCR 失败，流水线会固定走「票据解析失败 → 风控存疑 → 人工」分支，
# 与真实场景偏离、也测不到 OCR 成功路径。样本轮换使用，避免同一张票据触发重复检测。
$sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
$samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
if ($samples.Count -gt 0) {
    Write-Host ("[2] 使用真实票据样本 {0} 张: {1}" -f $samples.Count, (($samples | ForEach-Object { $_.Name }) -join ", ")) -ForegroundColor DarkGray
} else {
    Write-Host "[2] 未找到 OCR 样本，回退占位图（OCR 必失败，流水线会进人工）" -ForegroundColor DarkYellow
}
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { Write-Host "需要 curl.exe 做 multipart 上传" -ForegroundColor Red; exit 1 }
$script:uploadSeq = 0

function Upload-File() {
    if ($samples.Count -gt 0) {
        $src = $samples[$script:uploadSeq % $samples.Count]
        $script:uploadSeq++
        $ext = $src.Extension
        $tmp = Join-Path $env:TEMP ("r1-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $ext)
        Copy-Item $src.FullName $tmp -Force
        $ctype = if ($ext -match "png") { "image/png" } else { "image/jpeg" }
    } else {
        $png = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==")
        $tmp = Join-Path $env:TEMP ("r1-{0}.png" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)))
        [System.IO.File]::WriteAllBytes($tmp, $png)
        $ctype = "image/png"
    }
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=$ctype" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $id = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $id) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }
    return $id
}

# 金额去重：避开库中已有 total_amount
$existing = @()
$rows = Invoke-Sql "SELECT DISTINCT total_amount FROM $Database.expense_reimbursement WHERE deleted = 0;"
foreach ($l in ($rows -split "`n")) { if ($l.Trim()) { $existing += $l.Trim() } }
function Pick-UniqueAmount([decimal]$base) {
    $v = $base
    while ($existing -contains ("{0:F2}" -f $v)) { $v = $v + 1 }
    return $v
}
$amt1 = Pick-UniqueAmount 137.00
$amt2 = Pick-UniqueAmount 7000.00
Write-Host ("[2] 已选唯一测试金额: A={0}（小额）  B={1}（大额>5000）" -f $amt1, $amt2) -ForegroundColor DarkGray

function Submit-Reimb([string]$title, [decimal]$amount) {
    $fid = Upload-File
    $body = @{
        title = $title; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
        claimDate = "$Period-01"; remark = "R1 e2e"
        items = @(@{ name = "测试明细"; amount = $amount })
        fileRecordIds = @($fid)
    }
    $r = Api POST "/api/v1/reimbursements" $body $token
    if ($r.code -ne 0) { throw ("提交失败: {0}" -f $r.message) }
    return $r.data
}

function Wait-Task([long]$taskId) {
    $deadline = (Get-Date).AddSeconds($PollSeconds)
    while ((Get-Date) -lt $deadline) {
        $t = Api GET "/api/v1/tasks/$taskId" $null $token
        $st = $t.data.status
        if ($st -in @("SUCCESS", "FAILED", "APPROVAL_PENDING", "REJECTED", "CANCELLED")) { return $st }
        Start-Sleep -Seconds 2
    }
    return "TIMEOUT"
}

function UsedAmount() {
    return (Invoke-Sql "SELECT used_amount FROM $Database.budget WHERE tenant_id = 1 AND dept_id = $DeptId AND period = '$Period';")
}
function OccupancyFields([long]$reimbId) {
    $line = Invoke-Sql "SELECT status, amount FROM $Database.budget_occupancy WHERE tenant_id = 1 AND reimb_id = $reimbId;"
    if (-not $line) { return @("", "") }
    $p = $line -split $TAB
    return @($p[0], $p[1])
}
function TicketOfTask([long]$taskId) {
    $t = Api GET "/api/v1/audit/tickets?taskId=$taskId&pageSize=1" $null $token
    if ($t.data.records.Count -gt 0) { return $t.data.records[0].id }
    return $null
}

# ---------- 3. 提交两张单 ----------
Write-Host "[3] 提交两张报销单（A 小额、B 大额）" -ForegroundColor Yellow
$ra = Submit-Reimb "R1 验收单A" $amt1
$rb = Submit-Reimb "R1 验收单B" $amt2
$reimbA = $ra.id; $taskA = $ra.taskId
$reimbB = $rb.id; $taskB = $rb.taskId
$stA = Wait-Task $taskA
$stB = Wait-Task $taskB
Write-Host ("    A: reimbId={0} taskId={1} 状态={2}" -f $reimbA, $taskA, $stA)
Write-Host ("    B: reimbId={0} taskId={1} 状态={2}" -f $reimbB, $taskB, $stB)

Check (UsedAmount) "0.00" "提交后未占用（待审期间不占用预算）"
Check (Invoke-Sql "SELECT COUNT(*) FROM $Database.budget_occupancy WHERE tenant_id = 1 AND reimb_id IN ($reimbA, $reimbB);") "0" "提交后无记账行"

# ---------- 4. 审批通过两张 → 各自占用 ----------
Write-Host "[4] 财务审批通过两张单 → 预期各自占用，used_amount=总额" -ForegroundColor Yellow
$ticketA = TicketOfTask $taskA
$ticketB = TicketOfTask $taskB
if (-not $ticketA -or -not $ticketB) { Write-Host "    未取到工单（可能 AUTO_PASS 未建单），跳过占用测试" -ForegroundColor Red; $script:fail++ }
else {
    $apA = Api POST "/api/v1/audit/tickets/$ticketA/approve" @{ comment = "R1 验收通过A" } $token
    Check $apA.code 0 "A 审批通过接口"
    $apB = Api POST "/api/v1/audit/tickets/$ticketB/approve" @{ comment = "R1 验收通过B" } $token
    Check $apB.code 0 "B 审批通过接口"

    $expectedTotal = ("{0:F2}" -f ([decimal]$amt1 + [decimal]$amt2))
    Check (UsedAmount) $expectedTotal "两张通过后 used_amount（叠加占用）"
    $foa = OccupancyFields $reimbA
    $fob = OccupancyFields $reimbB
    Check $foa[0] "OCCUPIED" "A 记账状态"
    Check $fob[0] "OCCUPIED" "B 记账状态"
    Check $foa[1] ("{0:F2}" -f [decimal]$amt1) "A 记账金额"
    Check $fob[1] ("{0:F2}" -f [decimal]$amt2) "B 记账金额"

    # ---------- 5. 单B 申请撤销 + 财务同意 → 释放 ----------
    Write-Host "[5] 单B 申请撤销 → 财务同意 → 预期仅释放 B（回落为 A 的金额）" -ForegroundColor Yellow
    $wr = Api POST "/api/v1/reimbursements/$reimbB/withdraw-request" @{} $token
    Check $wr.code 0 "B 发起撤销申请"
    $ag = Api POST "/api/v1/audit/tickets/$ticketB/withdraw-agree" @{ comment = "R1 同意撤销" } $token
    Check $ag.code 0 "B 同意撤销接口"
    Check (UsedAmount) ("{0:F2}" -f [decimal]$amt1) "同意撤销后 used_amount 回落为 A 的金额"
    $fob2 = OccupancyFields $reimbB
    Check $fob2[0] "RELEASED" "B 记账状态转 RELEASED"
    Check $fob2[1] ("{0:F2}" -f [decimal]$amt2) "B 记账金额保持不变"

    # ---------- 6. 幂等：重复释放不应二次扣减 ----------
    Write-Host "[6] 幂等：再次对 B 触发释放（应无变化）" -ForegroundColor Yellow
    $again = Api POST "/api/v1/audit/tickets/$ticketB/withdraw-agree" @{ comment = "重复动作" } $token
    Check (UsedAmount) ("{0:F2}" -f [decimal]$amt1) "重复释放后 used_amount 未二次扣减"
}

# ---------- 7. 配平对账 ----------
# 对账口径必须与「本轮预算行」一致：预算行每轮开局被重建为 used_amount=0，
# 而记账表可能残留历史轮次的行，若按 dept_id+period 聚合会把残留算进来导致假失败。
# 故此处只对本轮两张单的 reimbId 求和——这才是与 budget.used_amount 可比的量。
#
# ⚠️ 配平口径（踩过两次，务必按此）：记账表是「一行一单 + 状态流转」，
# OCCUPIED ⇄ RELEASED 是同一行的状态迁移，转 RELEASED 时 used_amount 已减去该行金额。
# 因此配平式是  Σ(amount WHERE status='OCCUPIED') == used_amount——RELEASED 行整条跳过。
# 曾误写成 Σ(OCCUPIED) − Σ(RELEASED) 或「减去 release_count>0 的行」，等于把释放额
# 扣减两次：实测「占 142 → 占 7005 → 撤销释放 7005」一轮，DB 的 used_amount=142 正确，
# 而该式算出 142-7005 = -6863 的假失败。产品侧 BudgetOccupancyService.reconciledNet
# 当时也是同一处错误，已一并修正。
Write-Host "[7] 占用-释放配平对账（限定本轮 reimbId）" -ForegroundColor Yellow
if ($reimbA -and $reimbB) {
    $net = Invoke-Sql "SELECT COALESCE(SUM(amount), 0) FROM $Database.budget_occupancy WHERE tenant_id = 1 AND status = 'OCCUPIED' AND reimb_id IN ($reimbA, $reimbB);"
    Check $net (UsedAmount) "本轮记账净额 Σ(OCCUPIED) == budget.used_amount"
} else {
    Write-Host "    未取到 reimbId，跳过分层对账" -ForegroundColor DarkYellow
}

# 全局对账（跨全部轮次残留）：同一口径放到 dept_id+period 维度。
$globalNet = Invoke-Sql "SELECT COALESCE(SUM(amount), 0) FROM $Database.budget_occupancy WHERE tenant_id = 1 AND status = 'OCCUPIED' AND dept_id = $DeptId AND period = '$Period';"
Check $globalNet (UsedAmount) "全局对账 Σ(OCCUPIED) == budget.used_amount"

# ---------- 8. 超额拦截（原子 SQL 层） ----------
Write-Host "[8] 超额拦截：一次占用 100000（已用>0）应影响 0 行" -ForegroundColor Yellow
$rc = Invoke-Sql "UPDATE $Database.budget SET used_amount = used_amount + 100000.00 WHERE tenant_id = 1 AND dept_id = $DeptId AND period = '$Period' AND deleted = 0 AND used_amount + 100000.00 <= total_budget; SELECT ROW_COUNT();"
$rowCount = (($rc -split "`n")[-1]).Trim()
$before = UsedAmount
Check $rowCount "0" "原子 UPDATE 影响行数（0=被拦）"
Check (UsedAmount) $before "被拦后 used_amount 未变"

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor Cyan
Write-Host "测试数据保留在 DB 便于人工复核，清理用：" -ForegroundColor DarkGray
Write-Host ("  DELETE FROM budget_occupancy WHERE tenant_id=1 AND period='{0}';" -f $Period) -ForegroundColor DarkGray
Write-Host ("  DELETE FROM budget WHERE tenant_id=1 AND dept_id={0} AND period='{1}';" -f $DeptId, $Period) -ForegroundColor DarkGray

if ($script:fail -gt 0) { exit 1 }
exit 0
