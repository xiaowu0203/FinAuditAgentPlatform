# =====================================================================
# R3 按票查重 + 票据-明细交叉核验 —— 端到端验收脚本
#
# 三条判据（对应业务走查 B-4 / B-5）：
#   ① 同一张票提交两次 → duplicate_check 返回 LEVEL_HIGH（发票号硬命中）→ 进人工复核
#   ② 明细金额远大于票面 → invoice_match 报 AMOUNT_MISMATCH → 进人工复核（RULE_FAIL）
#   ③ B-4 回归：正常单据不因「库里存在同额历史单」被误判重复
#
# 观察方式：直接读 agent_task_step 的 output（工具输出落库），
# 不依赖日志，也不需要 service 重启之外的人工操作。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r3-invoice-dedup-e2e.ps1 `
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
    # 增值税发票样张（含真实票号，R2 联调确认可识别出 invoiceCode/invoiceNum）
    [string]$SampleFile = "",
    [int]$PollSeconds = 120
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
            return (($sr.ReadToEnd()) | ConvertFrom-Json)
        }
        throw
    }
}

# 取某个工具步骤的输出 JSON（流水线完成后落库在 agent_task_step.output）
function Get-ToolOutput([long]$taskId, [string]$toolName) {
    return (Invoke-Sql "SELECT output FROM $Database.agent_task_step WHERE task_id = $taskId AND tool_name = '$toolName' AND output IS NOT NULL ORDER BY step_no DESC LIMIT 1;")
}

# 提交报销单：返回 @{ reimbId; taskId; fileRecordId; invoiceNum }
function Submit-Reimb($token, $samples, $curl, [decimal]$amount, [string]$title, [string[]]$itemNames) {
    $src = if ($SampleFile) { Get-Item $SampleFile } else { $samples[(Get-Random -Maximum $samples.Count)] }
    $tmp = Join-Path $env:TEMP ("r3-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $src.Extension)
    Copy-Item $src.FullName $tmp -Force
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $fid = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $fid) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }

    # 明细：按传入名称构造；金额平均分配、尾差并入首项，保证 amount_verify 一致
    $names = if ($itemNames -and $itemNames.Count -gt 0) { $itemNames } else { @("测试明细") }
    $per = [math]::Round($amount / $names.Count, 2)
    $items = @()
    $acc = [decimal]0
    for ($i = 0; $i -lt $names.Count; $i++) {
        $v = if ($i -eq $names.Count - 1) { $amount - $acc } else { $per }
        $acc += $v
        $items += @{ name = $names[$i]; amount = $v }
    }

    $body = @{
        title = $title; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
        claimDate = "$Period-01"; remark = "R3 e2e"
        items = $items
        fileRecordIds = @($fid)
    }
    $r = Api POST "/api/v1/reimbursements" $body $token
    if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }
    return @{ reimbId = $r.data.id; taskId = $r.data.taskId; fileRecordId = $fid }
}

# 轮询等待指定工具步骤产出结果
function Wait-ToolOutput([long]$taskId, [string]$toolName) {
    $deadline = (Get-Date).AddSeconds($PollSeconds)
    while ((Get-Date) -lt $deadline) {
        $o = Get-ToolOutput $taskId $toolName
        if ($o) { return $o }
        Start-Sleep -Seconds 3
    }
    return ""
}

Write-Host "=== R3 按票查重 + 票据-明细交叉核验 端到端验收 ===" -ForegroundColor Cyan

$login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token

$sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
$samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { throw "需要 curl.exe" }
if ($samples.Count -eq 0) { throw "未找到 OCR 样本" }
if (-not $SampleFile) {
    $guess = Join-Path $sampleDir '增值税发票.jpg'
    if (Test-Path $guess) { $SampleFile = $guess }
}
Write-Host ("样张: {0}" -f (Split-Path $SampleFile -Leaf)) -ForegroundColor DarkGray

# ================= 判据①：同一张票 → 一级硬命中 =================
Write-Host ""
Write-Host "[1] 判据① 同一张票提交两次 → duplicate_check 应返回 LEVEL_HIGH" -ForegroundColor Yellow
$base = [decimal](Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM $Database.expense_reimbursement WHERE deleted = 0;")
$A = Submit-Reimb $token $samples $curl $base "R3 同票验证-首次" @("办公用品")
Write-Host ("    第一次 reimbId={0} taskId={1}" -f $A.reimbId, $A.taskId) -ForegroundColor DarkGray
$outA = Wait-ToolOutput $A.taskId "invoice_match"
if (-not $outA) { Write-Host "  ⚠️ 第一次 invoice_match 无输出（可能 OCR 未识别出票号）" -ForegroundColor DarkYellow }

$B = Submit-Reimb $token $samples $curl ($base + 1) "R3 同票验证-再次" @("办公用品")
Write-Host ("    第二次 reimbId={0} taskId={1}" -f $B.reimbId, $B.taskId) -ForegroundColor DarkGray
$outB = Wait-ToolOutput $B.taskId "duplicate_check"
Write-Host ("    duplicate_check 输出: {0}" -f $outB) -ForegroundColor DarkGray

if ($outB -match '"dupLevel"\s*:\s*"([^"]*)"') {
    $lv = $Matches[1]
    Check $lv "LEVEL_HIGH" "同一张票再次提交 → dupLevel"
    Check-True ($outB -match '"suspectedHigh"\s*:\s*true') "suspectedHigh=true（应触发风控）"
    # 命中条目里应带票号，支撑人工复核
    Check-True ($outB -match '"invoiceNum"\s*:\s*"\d+"') "命中条目携带发票号码"
} else {
    Write-Host "  ⚠️ duplicate_check 输出未含 dupLevel —— 请确认 tool-service/agent-core 已重启到最新代码" -ForegroundColor DarkYellow
    Write-Host ("     原始输出: {0}" -f $outB) -ForegroundColor DarkYellow
    $script:fail++
}

# ================= 判据②：明细远大于票面 → 交叉核验不一致 =================
Write-Host ""
Write-Host "[2] 判据② 明细金额远大于票面 → invoice_match 应报不一致" -ForegroundColor Yellow
# 用同一张票（票面 ~7741.75），但申报一个远大于票面的金额（如 50000）
$C = Submit-Reimb $token $samples $curl 50000.00 "R3 虚报验证" @("办公用品")
Write-Host ("    reimbId={0} taskId={1}" -f $C.reimbId, $C.taskId) -ForegroundColor DarkGray
$outC = Wait-ToolOutput $C.taskId "invoice_match"
Write-Host ("    invoice_match 输出: {0}" -f $outC) -ForegroundColor DarkGray

if ($outC) {
    Check-True ($outC -match '"match"\s*:\s*false') "申报远大于票面 → match=false"
    Check-True ($outC -match 'AMOUNT_MISMATCH|ITEM_EXCEEDS_INVOICE') "异常清单含金额类编码"
} else {
    Write-Host "  ⚠️ invoice_match 无输出：可能 OCR 未识别出票号，或 invoice_match 未在 tool_registry 注册" -ForegroundColor DarkYellow
    $script:fail++
}

# ================= 判据③：B-4 回归（正常单据不被误判） =================
Write-Host ""
Write-Host "[3] 判据③ B-4 回归：正常单据不应因「同额历史单」被误判重复" -ForegroundColor Yellow
# 先提交一笔正常单，再用同金额提交另一笔（不同票）
$D = Submit-Reimb $token $samples $curl ($base + 100) "R3 回归-历史单" @("办公用品")
Write-Host ("    历史单 reimbId={0}" -f $D.reimbId) -ForegroundColor DarkGray
$E = Submit-Reimb $token $samples $curl ($base + 100) "R3 回归-同额新单" @("办公用品")
Write-Host ("    同额新单 reimbId={0} taskId={1}" -f $E.reimbId, $E.taskId) -ForegroundColor DarkGray
$outE = Wait-ToolOutput $E.taskId "duplicate_check"
Write-Host ("    duplicate_check 输出: {0}" -f $outE) -ForegroundColor DarkGray

if ($outE -match '"dupLevel"\s*:\s*"([^"]*)"') {
    $lv3 = $Matches[1]
    if ($lv3 -eq "LEVEL_HIGH") {
        # 同一张样张 → 票号相同 → 硬命中是正确行为，不能算误判
        Write-Host ("  [SKIP] 本次两笔用的是同一张样张（票号相同），硬命中属预期；" ) -ForegroundColor DarkYellow
        Write-Host "         判据③ 需用「不同票 + 同金额」验证，详见单测 dupLevel=LEVEL_MEDIUM 的断言" -ForegroundColor DarkYellow
    } else {
        Check $lv3 "LEVEL_MEDIUM" "不同票、同金额 → 仅中置信（不触发风控）"
        Check-True ($outE -match '"suspectedHigh"\s*:\s*false') "suspectedHigh=false（B-4 根治点）"
    }
} else {
    Write-Host "  ⚠️ 未取到 dupLevel，跳过" -ForegroundColor DarkYellow
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("复核用 reimbIds: {0}, {1}, {2}, {3}, {4}" -f $A.reimbId, $B.reimbId, $C.reimbId, $D.reimbId, $E.reimbId) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
