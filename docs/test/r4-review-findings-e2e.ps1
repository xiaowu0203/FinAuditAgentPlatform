# =====================================================================
# R4 结构化审核问题项（findings）—— 端到端验收脚本
#
# 验收目标（对应业务走查 B-7：驳回只给一句文字，提交人不知道该改哪一行、改成多少）：
#   ① 触发差旅住宿超标 → 工单 review_findings 含：明细行下标、明细名、标准值、实际值、差额、建议
#   ② trigger_type 优先级：同时命中大额限额与差旅超标时，应取 OVER_LIMIT（不是 RULE_FAIL）
#   ③ reasons 仍是 findings 的派生视图（两者条数一致），review_reasons 兼容字段仍在
#
# 观察方式：直接读 audit_ticket.review_findings / review_reasons / trigger_type，不依赖日志。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r4-review-findings-e2e.ps1 `
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

# 提交一张报销单并等待流水线建出工单，返回工单号
function Submit-And-WaitTicket($token, $samples, $curl, $items, [decimal]$claimTotal, [string]$title) {
    $src = $samples[(Get-Random -Maximum $samples.Count)]
    $tmp = Join-Path $env:TEMP ("r4-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $src.Extension)
    Copy-Item $src.FullName $tmp -Force
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $fid = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $fid) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }

    $body = @{
        title = $title; expenseType = "TRAVEL"; deptName = $DeptName; deptId = $DeptId
        claimDate = "$Period-01"; remark = "R4 findings e2e"
        items = $items
        fileRecordIds = @($fid)
    }
    $r = Api POST "/api/v1/reimbursements" $body $token
    if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }

    $deadline = (Get-Date).AddSeconds($PollSeconds)
    while ((Get-Date) -lt $deadline) {
        $t = Invoke-Sql "SELECT id FROM $Database.audit_ticket WHERE task_id = $($r.data.taskId) AND deleted = 0 LIMIT 1;"
        if ($t) { return @{ reimbId = $r.data.id; ticketId = $t } }
        Start-Sleep -Seconds 3
    }
    throw ("{0} 秒内未建出工单（taskId={1}）" -f $PollSeconds, $r.data.taskId)
}

Write-Host "=== R4 结构化审核问题项 端到端验收 ===" -ForegroundColor Cyan

$login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token

$sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
$samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { throw "需要 curl.exe" }
if ($samples.Count -eq 0) { throw "未找到 OCR 样本" }

# ===== 判据①：差旅住宿超标 → findings 带行定位与数值 =====
# 北京 hotelDaily=500：800元/1晚超标 300；总额 800 低于大额限额 8000，故不带 OVER_LIMIT
Write-Host ""
Write-Host "[1] 判据① 差旅住宿超标 → review_findings 应含行号/标准/实际/差额/建议" -ForegroundColor Yellow
$items1 = @(@{
    name = "北京出差住宿"; amount = 800.00; amountType = "住宿"; city = "北京"
    hotelDays = 1; hotelAmount = 800.00
})
$t1 = Submit-And-WaitTicket $token $samples $curl $items1 800.00 "R4 差旅超标验证"
Write-Host ("    工单 id={0}（reimbId={1}）" -f $t1.ticketId, $t1.reimbId) -ForegroundColor DarkGray

$row1 = Invoke-Sql "SELECT trigger_type, IFNULL(review_findings,''), IFNULL(JSON_LENGTH(review_findings),0), IFNULL(JSON_LENGTH(review_reasons),0) FROM $Database.audit_ticket WHERE id = $($t1.ticketId);"
Write-Host ("    trigger_type / findings / findings条数 / reasons条数 = {0}" -f $row1) -ForegroundColor DarkGray
$p1 = $row1 -split "`t"

Check $p1[0].Trim() "RULE_FAIL" "trig_type（仅规则超标，无大额限额）"
Check-True ([int]$p1[2] -gt 0) "review_findings 非空（结构化问题项已落库）"

$fj = Invoke-Sql "SELECT review_findings FROM $Database.audit_ticket WHERE id = $($t1.ticketId);"
Write-Host ("    findings JSON: {0}" -f $fj) -ForegroundColor DarkGray

Check-True ($fj -match '"code"\s*:\s*"TRAVEL_STANDARD"') "finding.code = TRAVEL_STANDARD"
Check-True ($fj -match '"level"\s*:\s*"RULE_FAIL"') "finding.level = RULE_FAIL"
Check-True ($fj -match '"itemIndex"\s*:\s*0') "finding.itemIndex = 0（定位到明细行）"
Check-True ($fj -match '"itemName"\s*:\s*"北京出差住宿"') "finding.itemName 为明细名称"
Check-True ($fj -match '"expected"\s*:\s*500') "finding.expected = 500（标准值，单日标准×1晚）"
Check-True ($fj -match '"actual"\s*:\s*800') "finding.actual = 800（实际值）"
Check-True ($fj -match '"gap"\s*:\s*300') "finding.gap = 300（差额，自动算出）"
Check-True ($fj -match '"suggestion"\s*:\s*"[^"]+"') "finding.suggestion 非空（修改建议）"

# review_reasons 是 findings 的派生视图：它才承载给人读的文案（行号 + 标准/实际/超差额）。
# 结构化层用 itemIndex 表达行号，不掺中文文案——断言必须查对应的列（曾查错列导致假失败）。
$rr = Invoke-Sql "SELECT review_reasons FROM $Database.audit_ticket WHERE id = $($t1.ticketId);"
Write-Host ("    reasons JSON: {0}" -f $rr) -ForegroundColor DarkGray
Check-True ($rr -match '第1行') "reasons 文案含行号（供人工快速定位）"
Check-True ($rr -match '实际\s*800\s*/\s*标准\s*500') "reasons 文案含「实际/标准」"
Check-True ($rr -match '超\s*300') "reasons 文案含超差额"
Check $p1[3].Trim() $p1[2].Trim() "reasons 条数与 findings 一致（派生视图，非独立数据源）"

# ===== 判据②：同时命中大额限额 → triggerType 应取 OVER_LIMIT =====
Write-Host ""
Write-Host "[2] 判据② 同时命中大额限额 → triggerType 应取 OVER_LIMIT（优先级）" -ForegroundColor Yellow
$items2 = @(@{
    name = "北京出差住宿"; amount = 9000.00; amountType = "住宿"; city = "北京"
    hotelDays = 1; hotelAmount = 9000.00
})
$t2 = Submit-And-WaitTicket $token $samples $curl $items2 9000.00 "R4 大额+超标验证"
$row2 = Invoke-Sql "SELECT trigger_type, IFNULL(review_findings,'') FROM $Database.audit_ticket WHERE id = $($t2.ticketId);"
Write-Host ("    工单 id={0}  trigger_type/findings = {1}" -f $t2.ticketId, $row2) -ForegroundColor DarkGray

$p2 = $row2 -split "`t"
Check $p2[0].Trim() "OVER_LIMIT" "trigger_type（OVER_LIMIT 优先于 RULE_FAIL）"
Check-True ($p2[1] -match '"code"\s*:\s*"AMOUNT_LIMIT"') "含 AMOUNT_LIMIT 问题项"
Check-True ($p2[1] -match '"level"\s*:\s*"OVER_LIMIT"') "该问题项级别为 OVER_LIMIT"
Check-True ($p2[1] -match '"expected"\s*:\s*8000') "大额限额标准值 = 8000"
Check-True ($p2[1] -match '"code"\s*:\s*"TRAVEL_STANDARD"') "同时含差旅标准问题项（两项独立定位）"

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("复核用 ticketId: {0}, {1}" -f $t1.ticketId, $t2.ticketId) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
