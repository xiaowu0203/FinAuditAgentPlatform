# =====================================================================
# R8-2 主动通知 端到端验证脚本（站内信 + Webhook）
#
# 验证「通知真的发到了对的人、Webhook 真的被签名投递出去」，而不是只看代码写完：
#   A. 站内信：提交一单 → 命中转人工 → 申请人收到 TASK_NEED_REVIEW、审批人收到 TICKET_CREATED；
#      未读数 / 标记已读 / 分页 三个端点口径一致
#   B. Webhook 成功投递：本地 HttpListener 充当接收方 → 断言收到请求、
#      **用服务端同一算法复算 HMAC 签名通过**、事件头与负载 eventId 一致、台账转 SUCCESS
#   C. Webhook 失败处置：对端返回 500 且 maxAttempts=1 → 断言台账 DEAD + lastError 记录 HTTP 500
#      + 给配置创建人写了 WEBHOOK_DEAD 告警站内信
#   D. 安全：配置私网地址时被拒（除非显式开启 allow-private-address）
#
# 前置条件：
#   1) MySQL/Redis/RabbitMQ/Nacos/MinIO 已启动；agent-core 已按最新代码重启
#   2) 数据库已执行 docs/database/migration-P3.8.sql（含 §19 通知三表 + §20 notify:manage 权限码）
#   3) **agent-core 必须开启内网投递**：.env 中设 FINAUDIT_NOTIFY_ALLOW_PRIVATE_ADDRESS=true
#      （本脚本的接收方跑在 127.0.0.1，默认会被 SSRF 防线拒绝）；验证完请改回 false
#   4) 若把 delivery-enabled 设为 false，则必须依赖本脚本的 -DeliverNow 手工触发（默认已如此）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\r8-notify-e2e.ps1 `
#       -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
#   ... -SkipSubmit           # 跳过"提交一单"（不消耗 LLM/OCR 配额），只验 Webhook 与安全项
#   ... -Port 18099           # 接收方端口
# =====================================================================

param(
    [string]$Gateway = "http://localhost:9080",
    [string]$CoreBase = "http://localhost:9201",
    [string]$MySqlExe = "mysql",
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [int]$Port = 18099,
    [string]$SampleFile = "",
    [int]$PipelineTimeoutSeconds = 180,
    # 用一个既有【待审】工单执行真实审批动作来触发业务事件（零 LLM 配额）。
    # 相比 -SkipSubmit 提交新单，这条路径同样验证"业务事件 → 站内信 + Webhook 投递 + 签名"，
    # 但不需要跑流水线；代价是会把该工单推进到终态（测试数据专用）。
    [long]$ApproveTicketId = 0,
    [switch]$SkipSubmit
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$script:pass = 0
$script:fail = 0
$script:skip = 0

# 与 agent-core 约定一致的签名口径
$SIGN_PREFIX = "sha256="
$HOOK_PATH_OK = "/hook/ok"
$HOOK_PATH_FAIL = "/hook/fail"

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

function Invoke-Sql([string]$sql) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $MySqlExe -h $DbHost -P $DbPort -u $User "-p$Password" -D $Database --default-character-set=utf8mb4 -N -B -e $sql 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    $lines = @($raw | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'Using a password' })
    if ($code -ne 0) { throw ("SQL 失败: {0}" -f ($lines -join ' / ')) }
    return (($lines -join "`n").Trim())
}

# ⚠️ 本仓 JSON 响应头不带 charset，PS 5.1 会按 Latin-1 解码中文 → 必须显式按 UTF-8 解码原始字节，
#    否则任何"按字面比对中文"的断言都会误判（R8-3 已踩过，见 AGENTS.md §5.16）
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
    $params = @{ Method = $method; Uri = $uri; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
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

# ---------- 本地接收方（HttpListener + 后台 runspace） ----------
function Start-HookReceiver([int]$port, [string]$logFile, [string]$failPath) {
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$port/")
    $listener.Start()
    $ps = [powershell]::Create()
    [void]$ps.AddScript({
        param($listener, $logFile, $failPath)
        while ($listener.IsListening) {
            try { $ctx = $listener.GetContext() } catch { break }
            try {
                $reader = New-Object System.IO.StreamReader($ctx.Request.InputStream, [System.Text.Encoding]::UTF8)
                $body = $reader.ReadToEnd()
                $reader.Close()
                $headers = @{}
                foreach ($k in $ctx.Request.Headers.AllKeys) { $headers[$k] = $ctx.Request.Headers[$k] }
                $fail = ($ctx.Request.Url.AbsolutePath -eq $failPath)
                $record = [pscustomobject]@{
                    Path    = $ctx.Request.Url.AbsolutePath
                    Body    = $body
                    Headers = $headers
                    At      = (Get-Date).ToString('HH:mm:ss.fff')
                }
                Add-Content -LiteralPath $logFile -Value ($record | ConvertTo-Json -Depth 6 -Compress) -Encoding UTF8
                $ctx.Response.StatusCode = if ($fail) { 500 } else { 200 }
                $payload = [System.Text.Encoding]::UTF8.GetBytes('{"ok":true}')
                $ctx.Response.ContentType = "application/json"
                $ctx.Response.OutputStream.Write($payload, 0, $payload.Length)
                $ctx.Response.Close()
            } catch {
                try { $ctx.Response.StatusCode = 500; $ctx.Response.Close() } catch { }
            }
        }
    }).AddArgument($listener).AddArgument($logFile).AddArgument($failPath)
    [void]$ps.BeginInvoke()
    return [pscustomobject]@{ Listener = $listener; Ps = $ps }
}

# ⚠️ 读取侧必须用 @(管道) 形式：函数 `return @()` 会被 PowerShell 解包成 $null，
#    于是 `$x.Count` 打印成空字符串（本次实测踩到：对端明明回了 200，却报"收到 0 次请求"）。
# ⚠️ 请求记录落文件而非共享数组：跨 runspace 传 .NET 集合曾实测"父脚本看不到任何记录"，
#    文件是进程内最稳的通道，也便于事后取证。
function Get-HookCalls([string]$logFile, [string]$path) {
    $lines = @(Get-Content -LiteralPath $logFile -Encoding UTF8 -ErrorAction SilentlyContinue | Where-Object { $_ })
    return @($lines | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.Path -eq $path })
}

# ⚠️ PSCustomObject **不能**用 ["键"] 索引（会静默返回 $null，不报错）——必须走 PSObject.Properties。
#    实测踩到：接收记录里 4 个 X-Finaudit-* 头明明都在，脚本却报"事件头为空、签名不符"。
function Header-Of($record, [string]$name) {
    $prop = $record.Headers.PSObject.Properties[$name]
    if ($prop) { return $prop.Value }
    return $null
}
function New-Signature([string]$secret, [string]$timestamp, [string]$body) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($secret)
    $base = "$timestamp.$body"
    $hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($base))
    return ($hash | ForEach-Object { $_.ToString('x2') }) -join ''
}

Write-Host "=== R8-2 主动通知 端到端验证 ===" -ForegroundColor Cyan

$login = Api POST "$Gateway/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
$token = $login.data.token
# ⚠️ 登录响应是 { token, tokenType, expiresIn, user:{ id, ... } }（LoginVO），**没有顶层 userId**
$adminUserId = $login.data.user.id
if (-not $adminUserId) { throw "登录响应缺少 user.id，无法定位收件人" }
Write-Host ("    登录成功 userId={0}" -f $adminUserId) -ForegroundColor DarkGray

# 接收到的请求逐行写入该文件（跨 runspace 最稳的通道）
$hookLog = Join-Path $env:TEMP ('r8-hook-' + (Get-Date).ToString('HHmmss') + '.jsonl')
$receiver = Start-HookReceiver -port $Port -logFile $hookLog -failPath $HOOK_PATH_FAIL
Write-Host ("[1] 本地接收方已启动: http://127.0.0.1:{0}{1}（失败路径 {2} 固定返回 500）" -f $Port, $HOOK_PATH_OK, $HOOK_PATH_FAIL) -ForegroundColor DarkGray

$suffix = (Get-Date).ToString('HHmmss')
$secretOk = "r8-secret-ok-$suffix"
$secretFail = "r8-secret-fail-$suffix"
$hookOkId = $null
$hookFailId = $null
# 内网地址是否可配（取决于 agent-core 的 allow-private-address）；为 false 时跳过 Webhook 判据而不是整体中止
$webhookUsable = $false

try {
    # ---------- 判据 D：SSRF 防线 ----------
    Write-Host "[2] 判据D：地址安全校验（SSRF 防线）" -ForegroundColor Yellow
    $badScheme = Api POST "$Gateway/api/v1/notify/webhooks" @{
        name = "r8-bad-scheme-$suffix"; url = "ftp://127.0.0.1/x"; secret = "k" } $token
    Check-True ($badScheme.code -ne 0) ("非 http/https 地址被拒（实际 code={0} msg={1}）" -f $badScheme.code, $badScheme.message)

    # 内网地址：仅在 agent-core 开启 allow-private-address 后才可配置（默认关闭，是 SSRF 防线的一部分）
    $createProbe = Api POST "$Gateway/api/v1/notify/webhooks" @{
        name = "r8-probe-$suffix"; url = "http://127.0.0.1:$Port$HOOK_PATH_OK"; secret = "probe" } $token
    if ($createProbe.code -eq 0) {
        Check-True $true "内网地址在 allow-private-address=true 下可配置（本次已开启）"
        [void](Api DELETE "$Gateway/api/v1/notify/webhooks/$($createProbe.data.id)" $null $token)
        $webhookUsable = $true
    } else {
        Note-Skip ("内网地址被拒：{0} —— 前置条件未满足（agent-core 需以 FINAUDIT_NOTIFY_ALLOW_PRIVATE_ADDRESS=true 启动），非产品缺陷" -f $createProbe.message)
        Write-Host "       判据 B/C（Webhook 投递链路）本次无法执行；仍继续执行判据 A（站内信）" -ForegroundColor DarkYellow
    }

    if ($webhookUsable) {
    # ---------- 建两个 Webhook 配置 ----------
    Write-Host "[3] 建 Webhook 配置（成功路径 + 必败路径）" -ForegroundColor Yellow
    $createOk = Api POST "$Gateway/api/v1/notify/webhooks" @{
        name = "r8-ok-$suffix"; url = "http://127.0.0.1:$Port$HOOK_PATH_OK"; secret = $secretOk
        eventTypes = @(); enabled = 1; maxAttempts = 3; timeoutMs = 3000 } $token
    Check-True ($createOk.code -eq 0) ("成功路径配置已创建（code={0}）" -f $createOk.code)
    $hookOkId = $createOk.data.id
    Check-True ($createOk.data.secretMasked -like "*`*`*`*`**") ("响应中密钥为掩码而非明文（{0}）" -f $createOk.data.secretMasked)
    Check-True ($createOk.data.secretMasked -notlike "*$secretOk*") "掩码不含明文密钥"

    $createFail = Api POST "$Gateway/api/v1/notify/webhooks" @{
        name = "r8-fail-$suffix"; url = "http://127.0.0.1:$Port$HOOK_PATH_FAIL"; secret = $secretFail
        eventTypes = @("WEBHOOK_TEST"); enabled = 1; maxAttempts = 1; timeoutMs = 3000 } $token
    Check-True ($createFail.code -eq 0) ("必败路径配置已创建（maxAttempts=1；code={0}）" -f $createFail.code)
    $hookFailId = $createFail.data.id

    $types = Api GET "$Gateway/api/v1/notify/webhooks/event-types" $null $token
    Check-True ($types.code -eq 0 -and @($types.data).Count -ge 10) ("事件码目录可读（{0} 个）" -f @($types.data).Count)

    # ---------- 判据 B：成功投递（测试投递走完整 outbox 链路） ----------
    Write-Host "[4] 判据B：Webhook 成功投递 + 签名校验" -ForegroundColor Yellow
    $test = Api POST "$Gateway/api/v1/notify/webhooks/$hookOkId/test" $null $token
    Check-True ($test.code -eq 0) ("测试投递已登记（deliveryId={0}）" -f $test.data)
    $testDeliveryId = $test.data
    if (-not $testDeliveryId) {
        # 兜底：若运行中的构建尚未修复"自定义 insertBatch 不回填自增主键"（端点返回 null），
        # 用 SQL 取本 Webhook 最新一条，保证后续断言仍可执行；修复后应直接返回 id。
        $testDeliveryId = Invoke-Sql "SELECT IFNULL(MAX(id), 0) FROM notify_delivery WHERE webhook_id = $hookOkId;"
        Write-Host ("    ⚠️ 端点未返回 deliveryId（返回 null），已用 SQL 兜底取到 id={0}；请确认 agent-core 是否为最新构建" -f $testDeliveryId) -ForegroundColor DarkYellow
    }
    Check-True ([int]$testDeliveryId -gt 0) "取到投递记录 id（供台账核对）"

    $round = Api POST "$CoreBase/internal/notify/deliver" $null $null
    Write-Host ("    手工触发投递轮次: attempted={0} succeeded={1} dead={2}" -f $round.data.attempted, $round.data.succeeded, $round.data.dead) -ForegroundColor DarkGray
    Start-Sleep -Milliseconds 500

    $calls = @(Get-HookCalls $hookLog $HOOK_PATH_OK)
    Check-True ($calls.Count -ge 1) ("接收方收到请求（{0} 次）" -f $calls.Count)
    if ($calls.Count -ge 1) {
        $call = $calls[0]
        $ts = (Header-Of $call 'X-Finaudit-Timestamp')
        $sig = (Header-Of $call 'X-Finaudit-Signature')
        $expected = New-Signature -secret $secretOk -timestamp $ts -body $call.Body
        Check-True ($sig -eq ($SIGN_PREFIX + $expected)) "HMAC 签名经服务端算法复算通过（防篡改/防重放的基础）"
        Check-True ((Header-Of $call 'X-Finaudit-Event') -eq "WEBHOOK_TEST") ("事件头正确（{0}）" -f (Header-Of $call 'X-Finaudit-Event'))
        $payload = $call.Body | ConvertFrom-Json
        Check-True ($payload.eventId -eq (Header-Of $call 'X-Finaudit-Delivery')) "负载 eventId 与投递头一致（接收方幂等去重依据）"
        Check-True (-not [string]::IsNullOrWhiteSpace($payload.occurredAt)) "负载含 occurredAt"
        # 篡改一个字符后必须验签失败（否则"签名"只是装饰）
        $tampered = New-Signature -secret $secretOk -timestamp $ts -body ($call.Body + " ")
        Check-True ($tampered -ne $expected) "body 被改一个字符 ⇒ 签名不再匹配"
    }

    $deliveryRow = Invoke-Sql "SELECT status, attempt_count, IFNULL(last_http_status,-1) FROM notify_delivery WHERE webhook_id = $hookOkId ORDER BY id DESC LIMIT 1;"
    $dr = @($deliveryRow -split "`t")
    Check-True ($dr[0] -eq 'SUCCESS') ("投递台账已转 SUCCESS（实际 {0}）" -f $deliveryRow)

    # ---------- 判据 C：失败 → 重试耗尽 → DEAD + 告警站内信 ----------
    Write-Host "[5] 判据C：投递失败处置（对端 500 ⇒ DEAD + 告警）" -ForegroundColor Yellow
    $deadMsgBefore = Invoke-Sql "SELECT COUNT(*) FROM notify_message WHERE tenant_id = 1 AND event_type = 'WEBHOOK_DEAD' AND biz_id = $hookFailId;"
    $testFail = Api POST "$Gateway/api/v1/notify/webhooks/$hookFailId/test" $null $token
    Check-True ($testFail.code -eq 0) ("必败路径测试投递已登记（deliveryId={0}）" -f $testFail.data)
    $failDeliveryId = $testFail.data
    if (-not $failDeliveryId) {
        $failDeliveryId = Invoke-Sql "SELECT IFNULL(MAX(id), 0) FROM notify_delivery WHERE webhook_id = $hookFailId;"
        Write-Host ("    ⚠️ 端点未返回 deliveryId，已用 SQL 兜底取到 id={0}" -f $failDeliveryId) -ForegroundColor DarkYellow
    }

    $round2 = Api POST "$CoreBase/internal/notify/deliver" $null $null
    Write-Host ("    投递轮次: attempted={0} succeeded={1} dead={2}" -f $round2.data.attempted, $round2.data.succeeded, $round2.data.dead) -ForegroundColor DarkGray

    $failRow = Invoke-Sql "SELECT status, attempt_count, IFNULL(last_http_status,-1), IFNULL(last_error,'') FROM notify_delivery WHERE webhook_id = $hookFailId ORDER BY id DESC LIMIT 1;"
    $fr = @($failRow -split "`t")
    Check-True ($fr[0] -eq 'DEAD') ("maxAttempts=1 投递失败即置 DEAD（实际 {0}）" -f $fr[0])
    Check-True ([int]$fr[2] -eq 500) ("台账记录 HTTP 状态码 500（实际 {0}）" -f $fr[2])
    Check-True ($fr[3] -ne '') "台账记录失败原因（排障依据）"

    $deadMsgAfter = Invoke-Sql "SELECT COUNT(*) FROM notify_message WHERE tenant_id = 1 AND event_type = 'WEBHOOK_DEAD' AND biz_id = $hookFailId;"
    Check-True ([int]$deadMsgAfter -eq ([int]$deadMsgBefore + 1)) ("配置创建人收到 WEBHOOK_DEAD 告警站内信（{0} → {1}）" -f $deadMsgBefore, $deadMsgAfter)

    # 人工重投：应重新排期（PENDING 且次数清零）
    $retry = Api POST "$Gateway/api/v1/notify/webhooks/deliveries/$failDeliveryId/retry" $null $token
    Check-True ($retry.code -eq 0 -and $retry.data -eq $true) "人工重投接口可重置记录"
    $retryRow = Invoke-Sql "SELECT status, attempt_count FROM notify_delivery WHERE id = $failDeliveryId;"
    Check-True ($retryRow -like "PENDING*0") ("重投后状态 PENDING 且次数清零（实际 {0}）" -f $retryRow)
    } else {
        Note-Skip "判据B/C（Webhook 投递链路 + 签名 + DEAD 告警）因内网投递未开启而跳过"
    }

    # ---------- 判据 A：站内信 ----------
    if ($ApproveTicketId -gt 0) {
        Write-Host ("[6] 判据A（零配额入口）：既有待审工单 #{0} 触发真实审批事件" -f $ApproveTicketId) -ForegroundColor Yellow
        $before = Invoke-Sql "SELECT COUNT(*) FROM notify_message WHERE tenant_id = 1 AND event_type = 'TICKET_APPROVED' AND biz_id = $ApproveTicketId;"
        $act = Api POST "$Gateway/api/v1/audit/tickets/$ApproveTicketId/approve" @{ comment = "R8-2 通知验证：审批通过" } $token
        Check-True ($act.code -eq 0) ("审批动作执行成功（code={0}）" -f $act.code)

        $after = Invoke-Sql "SELECT COUNT(*) FROM notify_message WHERE tenant_id = 1 AND event_type = 'TICKET_APPROVED' AND biz_id = $ApproveTicketId;"
        Check-True ([int]$after -eq ([int]$before + 1)) ("申请人收到 TICKET_APPROVED 站内信（{0} → {1}）" -f $before, $after)
        $row = Invoke-Sql "SELECT user_id, title, IFNULL(link,''), category FROM notify_message WHERE event_type = 'TICKET_APPROVED' AND biz_id = $ApproveTicketId ORDER BY id DESC LIMIT 1;"
        $rf = @($row -split "`t")
        Check-True ($rf[0] -eq "$adminUserId") ("收件人是申请人本人（{0}）" -f $rf[0])
        Check-True ($rf[1] -eq '报销单已通过') ("标题可读（{0}）" -f $rf[1])
        Check-True ($rf[2] -eq "/audits/$ApproveTicketId") ("跳转路径指向该工单（{0}）" -f $rf[2])
        Check-True ($rf[3] -eq 'AUDIT') ("类别为 AUDIT（{0}）" -f $rf[3])

        if (-not $webhookUsable) {
            Note-Skip "业务事件的 Webhook 投递未验证（本次未配置任何 Webhook，前置条件不满足）"
        } else {
            # 催投 + 轮询等待：登记行的 next_retry_at 由 JVM 时钟写入**秒精度**列（MySQL 会四舍五入），
            # 刚登记的行可能还差零点几秒才算到期（实测踩到：手工催投一次当场漏掉该行，
            # 随后本脚本清理删掉配置，定时任务再取到时只能判 DEAD）。
            # 故这里不依赖亚秒时序：每 4 秒重催一次，最多等 ~20 秒，期间定时任务（15s 一轮）也会兜住。
            [void](Api POST "$CoreBase/internal/notify/deliver" $null $null)
            $ledger = ''
            $i = 0
            for ($i = 1; $i -le 25; $i++) {
                $ledger = Invoke-Sql "SELECT status FROM notify_delivery WHERE webhook_id = $hookOkId AND event_type = 'TICKET_APPROVED' ORDER BY id DESC LIMIT 1;"
                if ($ledger -ne 'PENDING') { break }
                if ($i % 5 -eq 0) { [void](Api POST "$CoreBase/internal/notify/deliver" $null $null) }
                Start-Sleep -Milliseconds 800
            }
            Write-Host ("    业务事件投递最终状态: {0}（轮询 {1} 次）" -f $ledger, $i) -ForegroundColor DarkGray
            $evCalls = @(@(Get-HookCalls $hookLog $HOOK_PATH_OK) | Where-Object { (Header-Of $_ 'X-Finaudit-Event') -eq 'TICKET_APPROVED' })
            Check-True ($evCalls.Count -ge 1) ("业务事件真的走了 Webhook 通道（{0} 条 TICKET_APPROVED 请求）" -f $evCalls.Count)
            if ($evCalls.Count -ge 1) {
                $ev = $evCalls[0]
                $ts2 = (Header-Of $ev 'X-Finaudit-Timestamp')
                $exp2 = New-Signature -secret $secretOk -timestamp $ts2 -body $ev.Body
                Check-True ((Header-Of $ev 'X-Finaudit-Signature') -eq ($SIGN_PREFIX + $exp2)) "业务事件请求签名同样复算通过"
                $pl = $ev.Body | ConvertFrom-Json
                Check-True ($pl.eventType -eq 'TICKET_APPROVED') ("负载事件类型正确（{0}）" -f $pl.eventType)
                Check-True ([long]$pl.bizId -eq $ApproveTicketId) ("负载 bizId 指向该工单（{0}）" -f $pl.bizId)
                Check-True ($pl.data.action -eq 'APPROVE') ("负载带审批动作（{0}）" -f $pl.data.action)
            }
            Check-True ($ledger -eq 'SUCCESS') ("业务事件投递台账为 SUCCESS（实际 {0}）" -f $ledger)
        }
    } elseif ($SkipSubmit) {
        Note-Skip "-SkipSubmit：跳过站内信业务链路断言（前置条件未执行，非产品缺陷）"
    } else {
        Write-Host "[6] 判据A：站内信（提交一单 → 转人工 → 双方收件）" -ForegroundColor Yellow
        $sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
        $samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
        $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
        if ($samples.Count -eq 0 -or -not $curl) { throw "缺少 OCR 样本或 curl.exe" }
        $sample = if ($SampleFile) { Get-Item $SampleFile } else { $samples[(Get-Random -Maximum $samples.Count)] }
        $tmp = Join-Path $env:TEMP ("r8n-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $sample.Extension)
        Copy-Item $sample.FullName $tmp -Force
        $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
        finally { $ErrorActionPreference = $prev }
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
        $fid = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
        if (-not $fid) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }

        $base = [decimal](Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM expense_reimbursement WHERE deleted = 0;")
        $r = Api POST "$Gateway/api/v1/reimbursements" @{
            title = "R8-2 通知验证"; expenseType = "OFFICE"; deptName = "研发部"; deptId = 3
            claimDate = "2026-09-01"; remark = "R8-2 notify check"
            items = @(@{ name = "测试明细"; amount = $base }); fileRecordIds = @($fid) } $token
        if ($r.code -ne 0) { throw ("提交失败: code={0} msg={1}" -f $r.code, $r.message) }
        $taskId = $r.data.taskId
        Write-Host ("    已提交 taskId={0}（真实 LLM 调用）" -f $taskId) -ForegroundColor DarkGray

        $deadline = (Get-Date).AddSeconds($PipelineTimeoutSeconds)
        $status = ''
        while ((Get-Date) -lt $deadline) {
            $status = Invoke-Sql "SELECT status FROM agent_task WHERE id = $taskId;"
            if ($status -notin @('PENDING', 'RUNNING')) { break }
            Start-Sleep -Seconds 3
        }
        Write-Host ("    任务终态 status={0}" -f $status) -ForegroundColor DarkGray

        $needReview = Invoke-Sql "SELECT COUNT(*) FROM notify_message WHERE tenant_id = 1 AND user_id = $adminUserId AND event_type = 'TASK_NEED_REVIEW' AND biz_id = $taskId;"
        # TICKET_CREATED 的 biz_id 是工单 id（不是任务 id），故 join audit_ticket 定位到本任务
        $ticketCreated = Invoke-Sql "SELECT COUNT(*) FROM notify_message m JOIN audit_ticket t ON t.id = m.biz_id WHERE m.tenant_id = 1 AND m.user_id = $adminUserId AND m.event_type = 'TICKET_CREATED' AND t.task_id = $taskId;"
        $approverRole = Invoke-Sql "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role_permission rp ON rp.role_id = ur.role_id JOIN sys_permission p ON p.id = rp.perm_id WHERE ur.user_id = $adminUserId AND ur.deleted = 0 AND rp.deleted = 0 AND p.perm_code = 'audit:approve';"

        if ($status -eq 'APPROVAL_PENDING') {
            Check-True ([int]$needReview -ge 1) ("申请人收到 TASK_NEED_REVIEW 站内信（{0} 条）" -f $needReview)
            if ([int]$approverRole -ge 1) {
                Check-True ([int]$ticketCreated -ge 1) ("审批人（本租户 admin 持有 audit:approve）收到 TICKET_CREATED 站内信")
            } else {
                Note-Skip "当前用户不持有 audit:approve，无法断言审批人收件（前置条件不满足）"
            }
        } else {
            Note-Skip ("任务终态为 {0}（未走转人工）⇒ 站内信业务链路不适用；可换一张必然超标/大额的样本重跑" -f $status)
        }

        # 端点口径：未读数 / 标记已读 / 分页
        $unread = Api GET "$Gateway/api/v1/notify/messages/unread-count" $null $token
        Check-True ($unread.code -eq 0 -and [int]$unread.data.unread -ge 1) ("未读数端点可用且 > 0（{0}）" -f $unread.data.unread)

        $list = Api GET "$Gateway/api/v1/notify/messages?pageNum=1&pageSize=5&unreadOnly=true" $null $token
        Check-True ($list.code -eq 0 -and @($list.data.records).Count -ge 1) "未读消息分页可读"
        $firstId = $null
        if (@($list.data.records).Count -ge 1) {
            $firstId = $list.data.records[0].id
            Check-True ($list.data.records[0].read -eq $false) "未读消息 read=false"
        }

        if ($firstId) {
            $before = [int]$unread.data.unread
            $mark = Api POST "$Gateway/api/v1/notify/messages/$firstId/read" $null $token
            Check-True ($mark.code -eq 0 -and $mark.data -eq $true) "标记已读成功"
            $after = Api GET "$Gateway/api/v1/notify/messages/unread-count" $null $token
            Check-True ([int]$after.data.unread -eq ($before - 1)) ("未读数减 1（{0} → {1}）" -f $before, $after.data.unread)
        }

        # 事件驱动投递：转人工会产生 TICKET_CREATED 事件 → 应登记投递记录
        # ⚠️ 本判据依赖"本租户存在订阅该事件的启用 Webhook"，即与判据 B/C 同一个前置条件；
        #    未开启内网投递时没有任何 Webhook 配置，此处必须跳过而不是报 FAIL
        #    （否则就是把"前置条件不满足"当成产品缺陷——本仓踩坑清单第 18 条）
        if (-not $webhookUsable) {
            Note-Skip "业务事件的 Webhook 投递未验证（本次未配置任何 Webhook，前置条件不满足）"
        } elseif ($status -ne 'APPROVAL_PENDING') {
            Note-Skip ("任务终态为 {0}（未走转人工）⇒ 无 TICKET_CREATED 事件" -f $status)
        } else {
            [void](Api POST "$CoreBase/internal/notify/deliver" $null $null)
            Start-Sleep -Milliseconds 400
            $eventCalls = @(@(Get-HookCalls $hookLog $HOOK_PATH_OK) | Where-Object { (Header-Of $_ 'X-Finaudit-Event') -ne 'WEBHOOK_TEST' })
            Check-True ($eventCalls.Count -ge 1) ("业务事件也走了 Webhook 通道（{0} 条业务请求）" -f $eventCalls.Count)
            if ($eventCalls.Count -ge 1) {
                $ev = $eventCalls[0]
                $ts2 = (Header-Of $ev 'X-Finaudit-Timestamp')
                $exp2 = New-Signature -secret $secretOk -timestamp $ts2 -body $ev.Body
                Check-True ((Header-Of $ev 'X-Finaudit-Signature') -eq ($SIGN_PREFIX + $exp2)) "业务事件请求签名同样复算通过"
            }
        }
    }
} catch {
    if ($_.Exception.Message -ne "__PRECONDITION__") {
        Write-Host ("  脚本异常: {0}" -f $_.Exception.Message) -ForegroundColor Red
        $script:fail++
    }
} finally {
    # 清理：删除本次创建的配置（投递台账保留，逻辑删除不影响历史核对）
    try { $receiver.Listener.Stop(); $receiver.Listener.Close() } catch { }
    try { [void]$receiver.Ps.Stop() } catch { }
    foreach ($id in @($hookOkId, $hookFailId)) {
        if ($id) { try { [void](Api DELETE "$Gateway/api/v1/notify/webhooks/$id" $null $token) } catch { } }
    }
    Write-Host "[清理] 接收方已停止，本次创建的 Webhook 配置已逻辑删除" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} SKIP={2} ===" -f $script:pass, $script:fail, $script:skip) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ("核对 SQL: SELECT id,user_id,category,event_type,title,read_at FROM {0}.notify_message ORDER BY id DESC LIMIT 10;" -f $Database) -ForegroundColor DarkGray
Write-Host ("核对 SQL: SELECT id,webhook_id,event_type,status,attempt_count,last_http_status,last_error FROM {0}.notify_delivery ORDER BY id DESC LIMIT 10;" -f $Database) -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
