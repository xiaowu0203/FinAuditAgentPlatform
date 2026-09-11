# =====================================================================
# 审计时间戳自动填充 —— 运行时验证脚本（P3.8 R2 附带修复）
#
# 背景（R2 联调发现的框架级缺陷）：
#   DDL 里 updated_at 是 ON UPDATE CURRENT_TIMESTAMP，看似无需应用层赋值；
#   但 MyBatis-Plus 的 updateById(entity) 按 NOT_NULL 策略会把实体里【从库里读出的旧
#   updated_at】一并写进 SET。列一旦被显式赋值，MySQL 的 ON UPDATE 就不再触发 ——
#   于是"更新一行"把旧时间戳原样写回，updated_at 永远等于 created_at。
#
#   实测（修复前，invoice_record 同一行连续改写）：
#     BEFORE: seen_count=4 attachment_id=45 reimb_id=47 updated_at=01:37:22
#     AFTER : seen_count=5 attachment_id=46 reimb_id=48 updated_at=01:37:22  ← 三字段都变，时间戳不动
#
# 修复：common-mybatisplus-starter 新增 AuditTimestampMetaObjectHandler +
#       实体字段标注 @TableField(fill = FieldFill.INSERT_UPDATE)。
#
# 判据：对同一张票连续提交两次（第二次会走 updateById 累加 seen_count），
#       invoice_record.updated_at **必须晚于 created_at**。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r2-audit-timestamp-check.ps1 `
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

# 提交一张报销单并等待 OCR 回写，返回 @{ reimbId; attachmentId; fileRecordId; invoiceNum }
# 指定 SampleFile 时两次调用都用同一张图 —— 本脚本必须如此，否则第二张可能是别的发票，
# 走不到「命中既有票 → updateById 累加 seen_count」这条被验证的路径。
function Submit-And-Wait($token, $samples, $curl, [decimal]$amount, [string]$title) {
    $src = if ($SampleFile) { Get-Item $SampleFile } else { $samples[(Get-Random -Maximum $samples.Count)] }
    $tmp = Join-Path $env:TEMP ("audit-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $src.Extension)
    Copy-Item $src.FullName $tmp -Force
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $fid = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $fid) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }

    $body = @{
        title = $title; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
        claimDate = "$Period-01"; remark = "R2 audit-timestamp check"
        items = @(@{ name = "测试明细"; amount = $amount })
        fileRecordIds = @($fid)
    }
    $r = Api POST "/api/v1/reimbursements" $body $token
    if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }

    $deadline = (Get-Date).AddSeconds($PollSeconds)
    $attId = $null
    $invNum = $null
    while ((Get-Date) -lt $deadline) {
        $row = Invoke-Sql "SELECT id, ocr_status FROM $Database.expense_attachment WHERE tenant_id = 1 AND file_record_id = $fid AND deleted = 0 LIMIT 1;"
        if ($row) {
            $attId = ($row -split "`t")[0].Trim()
            $js = Invoke-Sql "SELECT ocr_result FROM $Database.expense_attachment WHERE id = $attId;"
            if ($js -match '"invoiceNum"\s*:\s*"([^"]*)"') { $invNum = $Matches[1] }
            if ($invNum) { break }
        }
        Start-Sleep -Seconds 2
    }
    return @{ reimbId = $r.data.id; attachmentId = $attId; fileRecordId = $fid; invoiceNum = $invNum }
}

Write-Host "=== 审计时间戳自动填充 运行时验证 ===" -ForegroundColor Cyan

$login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token

$sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
$samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { throw "需要 curl.exe" }
if ($samples.Count -eq 0) { throw "未找到 OCR 样本" }

$maxAmt = [decimal](Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM $Database.expense_reimbursement WHERE deleted = 0;")

Write-Host "[1] 第一次提交（建立/命中投影行）" -ForegroundColor Yellow
$r1 = Submit-And-Wait $token $samples $curl $maxAmt "审计时间戳验证1"
Write-Host ("    reimbId={0} invoiceNum='{1}'" -f $r1.reimbId, $r1.invoiceNum) -ForegroundColor DarkGray
if (-not $r1.invoiceNum) { throw "OCR 未识别出票号，无法验证投影行的 updated_at（属样本/配额限制）" }

$before = Invoke-Sql "SELECT created_at, updated_at, seen_count FROM $Database.invoice_record WHERE tenant_id = 1 AND invoice_num = '$($r1.invoiceNum)' AND deleted = 0;"
Write-Host ("    第一次后: {0}" -f $before) -ForegroundColor DarkGray
$bp = $before -split "`t"
$tsBefore = [datetime]::Parse($bp[1].Trim())
$seenBefore = [int]$bp[2].Trim()

Write-Host "[2] 第二次提交（同一张票 → 走 updateById 累加 seen_count）" -ForegroundColor Yellow
$r2 = Submit-And-Wait $token $samples $curl ($maxAmt + 1) "审计时间戳验证2"
Write-Host ("    reimbId={0} invoiceNum='{1}'" -f $r2.reimbId, $r2.invoiceNum) -ForegroundColor DarkGray

# ⚠️ 断言口径（踩过坑）：必须比对「两次提交之间 updated_at 是否跳变」，
#    而不是简单地与 created_at 比（diff>0）——后者会把**几十分钟前的历史偏差**
#    误判为"本次修复生效"。实测：修复无效时 seen_count 正常累加，
#    但 updated_at 冻结在旧实验留下的值上，diff=128 让弱断言假通过。
$after = $null
$tsAfter = $null
$seenAfter = 0
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    $after = Invoke-Sql "SELECT created_at, updated_at, seen_count FROM $Database.invoice_record WHERE tenant_id = 1 AND invoice_num = '$($r1.invoiceNum)' AND deleted = 0;"
    $ap = $after -split "`t"
    $tsAfter = [datetime]::Parse($ap[1].Trim())
    $seenAfter = [int]$ap[2].Trim()
    if ($tsAfter -gt $tsBefore) { break }
    Start-Sleep -Seconds 2
}
Write-Host ("    第二次后: {0}" -f $after) -ForegroundColor DarkGray

Check-True ($seenAfter -gt $seenBefore) ("seen_count 已累加（{0} → {1}），确认走了 updateById 路径" -f $seenBefore, $seenAfter)

$delta = [int]($tsAfter - $tsBefore).TotalSeconds
if ($tsAfter -gt $tsBefore) {
    Write-Host ("  [PASS] updated_at 在两次提交之间跳变：{0} → {1}（+{2} 秒）⇒ 自动填充生效" -f `
        $tsBefore.ToString('HH:mm:ss'), $tsAfter.ToString('HH:mm:ss'), $delta) -ForegroundColor Green
    $script:pass++
} else {
    Write-Host ("  [FAIL] updated_at 未跳变，仍为 {0} ⇒ 填充未生效" -f $tsAfter.ToString('HH:mm:ss')) -ForegroundColor Red
    Write-Host "         排查：① common-mybatisplus-starter 是否重新构建并重启" -ForegroundColor Red
    Write-Host "               ② updateFill 是否用 setFieldValByName（strictUpdateFill 会漏填已有值）" -ForegroundColor Red
    $script:fail++
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
if ($script:fail -gt 0) { exit 1 }
