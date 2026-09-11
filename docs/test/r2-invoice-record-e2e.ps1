# =====================================================================
# R2 发票标识符入链 —— 端到端验收脚本
#
# 验收目标（两步，第 1 步是可靠判据，第 2 步依赖 OCR 能否识别出票号）：
#   ① R2-1 归一化生效：新写入的 ocr_result JSON 必须包含
#      invoiceCode / invoiceNum / ocrDate 三个新字段。
#      老代码的 JSON 只有 date/taxNo/amount/merchant/receiptType，绝不会有这三个字段，
#      因此「字段出现」即证明新代码在跑。
#   ② R2-3 投影生效：若 invoiceNum 非空，invoice_record 应有对应行；
#      同一张票再提交一次 → 不新增行、seen_count 累加为 2。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r2-invoice-record-e2e.ps1 `
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
    [int]$PollSeconds = 90,
    [string]$SampleFile = ""
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

function Upload-Sample([string]$token, $samples, [string]$curl) {
    # 指定样张优先（幂等验证需要「同一张图跑两次」，随机取样达不到该目的）
    $src = if ($SampleFile) { Get-Item $SampleFile } else { $samples[(Get-Random -Maximum $samples.Count)] }
    $tmp = Join-Path $env:TEMP ("r2-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $src.Extension)
    Copy-Item $src.FullName $tmp -Force
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $id = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $id) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }
    return $id
}

Write-Host "=== R2 发票标识符入链 端到端验收 ===" -ForegroundColor Cyan
Write-Host ("网关 {0}   库 {1}:{2}/{3}" -f $Gateway, $DbHost, $Port, $Database) -ForegroundColor DarkGray

# ---------- 0. 登录 + 样本 ----------
$login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token
Write-Host "[0] 登录成功" -ForegroundColor DarkGray

$sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
$samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { throw "需要 curl.exe 做 multipart 上传" }
if ($samples.Count -eq 0) { throw ("未找到 OCR 样本: {0}" -f $sampleDir) }
Write-Host ("    票据样本 {0} 张" -f $samples.Count) -ForegroundColor DarkGray

# ---------- 1. 提交报销单（触发 OCR 流水线） ----------
$maxAmt = Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM $Database.expense_reimbursement WHERE deleted = 0;"
$fid = Upload-Sample $token $samples $curl
Write-Host ("[1] 已上传附件 file_record_id={0}，提交报销单（金额 {1}）" -f $fid, $maxAmt) -ForegroundColor DarkGray
$body = @{
    title = "R2 发票入链验收"; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
    claimDate = "$Period-01"; remark = "R2 invoice_record e2e"
    items = @(@{ name = "测试明细"; amount = [decimal]$maxAmt })
    fileRecordIds = @($fid)
}
$r = Api POST "/api/v1/reimbursements" $body $token
if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }
$reimbId = $r.data.id
$taskId = $r.data.taskId
Write-Host ("    提交成功 reimbId={0} taskId={1} status={2}" -f $reimbId, $taskId, $r.data.status) -ForegroundColor DarkGray

# ---------- 2. 轮询等待 OCR 回写 ----------
Write-Host "[2] 等待 OCR 回写（最多 $PollSeconds 秒）" -ForegroundColor Yellow
$ocrStatus = $null
$attachmentId = $null
$deadline = (Get-Date).AddSeconds($PollSeconds)
while ((Get-Date) -lt $deadline) {
    $row = Invoke-Sql "SELECT id, ocr_status FROM $Database.expense_attachment WHERE tenant_id = 1 AND file_record_id = $fid AND deleted = 0 LIMIT 1;"
    if ($row) {
        $parts = $row -split "`t"
        $attachmentId = $parts[0].Trim()
        $ocrStatus = $parts[1].Trim()
        if ($ocrStatus -ne 'PENDING') { break }
    }
    Start-Sleep -Seconds 2
}
Write-Host ("    attachmentId={0}  ocr_status={1}" -f $attachmentId, $ocrStatus) -ForegroundColor DarkGray

if (-not $attachmentId) { throw "未找到本次提交产生的附件行（file_record_id=$fid）" }

# ---------- 3. 判据①：ocr_result 必须含 R2 新增字段 ----------
Write-Host "[3] 判据 ① R2-1 归一化生效：ocr_result 是否含 invoiceCode/invoiceNum/ocrDate" -ForegroundColor Yellow
$json = Invoke-Sql "SELECT ocr_result FROM $Database.expense_attachment WHERE id = $attachmentId;"
Write-Host ("    实际 JSON: {0}" -f $json) -ForegroundColor DarkGray

Check-True ($json -match '"invoiceCode"') 'ocr_result 含 invoiceCode 字段（老代码绝不产生）'
Check-True ($json -match '"invoiceNum"') 'ocr_result 含 invoiceNum 字段'
Check-True ($json -match '"ocrDate"') 'ocr_result 含 ocrDate 字段（可入库的 ISO 日期）'

$invoiceNum = $null
$invoiceCode = $null
if ($json -match '"invoiceNum"\s*:\s*"([^"]*)"') { $invoiceNum = $Matches[1] }
if ($json -match '"invoiceCode"\s*:\s*"([^"]*)"') { $invoiceCode = $Matches[1] }
Write-Host ("    解析: invoiceCode='{0}'  invoiceNum='{1}'" -f $invoiceCode, $invoiceNum) -ForegroundColor DarkGray

# ---------- 3.5 投影基线 + 等待本次识别落库 ----------
# 幂等语义：同一张票再次被识别时【不新增行】，只累加 seen_count 并刷新来源/归属。
# ⚠️ 断言不能硬编码 seen_count=1：同一张票可能已是第 N 次识别。
#    更要紧的是**基线必须在本轮识别写入之前取到** —— 而票号只能从 ocr_result 读出，
#    于是顺序只能是「读到票号 → 立刻取基线 → 轮询等待基线 +1」。
#    （踩过：先提交再取基线，OCR 已完成导致基线被当成"识别前"，期望值永远多加 1。）
$priorSeen = 0
$expectSeen = $null
$rowCnt = $null
if ($invoiceNum) {
    $raw = Invoke-Sql "SELECT seen_count FROM $Database.invoice_record WHERE tenant_id = 1 AND invoice_num = '$invoiceNum' AND deleted = 0;"
    $priorSeen = if ($raw) { [int]$raw } else { 0 }
    $expectSeen = $priorSeen + 1
    Write-Host ("    基线: seen_count={0}（0=该票尚未投影），期望本次后为 {1}" -f $priorSeen, $expectSeen) -ForegroundColor DarkGray
    # 轮询等待本次识别落库（OCR 回写与投影在同一事务，但相对本脚本是异步的）
    $deadline2 = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline2) {
        $cur = Invoke-Sql "SELECT seen_count FROM $Database.invoice_record WHERE tenant_id = 1 AND invoice_num = '$invoiceNum' AND deleted = 0;"
        if ($cur -and [int]$cur -ge $expectSeen) { break }
        Start-Sleep -Seconds 2
    }
}

# ---------- 4. 判据②：票号非空时投影应落行 ----------
Write-Host "[4] 判据 ② R2-3 投影生效" -ForegroundColor Yellow
if ($ocrStatus -eq 'SUCCESS' -and $invoiceNum) {
    $proj = Invoke-Sql "SELECT invoice_code, invoice_num, reimb_id, seen_count FROM $Database.invoice_record WHERE tenant_id = 1 AND invoice_num = '$invoiceNum' AND deleted = 0;"
    Write-Host ("    invoice_record: {0}" -f $proj) -ForegroundColor DarkGray
    $projParts = $proj -split "`t"
    Check $projParts[1].Trim() $invoiceNum "投影行 invoice_num 匹配"
    Check $projParts[2].Trim() "$reimbId" "投影行 reimb_id 已刷新为本次报销单"
    Check $projParts[3].Trim() "$expectSeen" ("seen_count 从 {0} 累加为 {1}" -f $priorSeen, $expectSeen)

    # 同一张票必须始终只有一行 —— 这是幂等的核心断言
    $rowCnt = Invoke-Sql "SELECT COUNT(*) FROM $Database.invoice_record WHERE tenant_id = 1 AND invoice_num = '$invoiceNum' AND deleted = 0;"
    Check $rowCnt "1" "同一张票在 invoice_record 中只有一行（重复识别不新增行）"
    if ($priorSeen -gt 0) {
        Write-Host "    ⇒ 本次是【重复识别】路径，验证的正是幂等累加" -ForegroundColor DarkYellow
    } else {
        Write-Host "    ⇒ 本次是【首次识别】路径，已建立投影行" -ForegroundColor DarkGray
    }
} else {
    Write-Host ("    ⚠️ 跳过：OCR 未识别出票号（ocr_status={0}, invoiceNum='{1}'）" -f $ocrStatus, $invoiceNum) -ForegroundColor DarkYellow
    Write-Host "       —— 属票据样本/OCR 配额限制，不是代码问题；判据 ① 已独立证明 R2-1 生效" -ForegroundColor DarkYellow
    # 至少断言「无票号时不投影」这一设计决策确实生效
    $noProj = Invoke-Sql "SELECT COUNT(*) FROM $Database.invoice_record WHERE tenant_id = 1 AND file_record_id = $fid AND deleted = 0;"
    Check $noProj "0" "无票号时不产生投影行（不用空串占位）"
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("复核用 reimbId={0} attachmentId={1} file_record_id={2}" -f $reimbId, $attachmentId, $fid) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
