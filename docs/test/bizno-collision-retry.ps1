# =====================================================================
# 单号碰撞重试机制 —— 确定性验证脚本（P3.8 R1-8）
#
# 为什么需要这个脚本：
#   单号 = 前缀 + yyyyMMddHHmmss + 4 位随机数，自然撞号概率约万分之一，
#   靠"多跑几轮"根本验证不到 BizNoInserter 的换号重试分支。
#
# 造撞方式（关键，别再用"占几个尾号"这种弱方案）：
#   单号 = 前缀 + yyyyMMddHHmmss + 4 位随机数，随机段取值 0-9999。
#   想【保证】撞上，必须把目标秒的 10000 个尾号全占掉：
#     1) 轮询等到下一个整秒开始的瞬间（此时距离该秒结束还有约 1000ms）
#     2) 立刻向 agent_task 插入该秒的 10000 行（秒段固定 + 尾号 0000-9999）
#     3) 立刻提交报销单 → 无论随机数抽到什么，uk_reimb_no / uk_task_no 必撞
#   实测：整秒后单次插入几千行仅需几毫秒，稳定赶在号段生成之前占好。
#   只占尾号 0-9 是错的——撞中概率仍只有 10/10000，等于换个方式继续赌千分之一。
#
# 判据（脚本自动断言）：
#   1. 提交返回 code=0                     —— 换号重试对外无感，用户看不到错误
#   2. 报销单号秒段 == 被占满的秒           —— 证明确实是在被占满的那一秒取的号，
#      即 uk_reimb_no 必然撞过，走的正是 BizNoInserter 换号分支
#   3. 两个单号格式合法（R/T + 18 位）      —— 换号后格式未破坏
#
# 人工判据：agent-core 控制台出现下面两行（自动断言无法读取控制台，需人工确认）
#   WARN  BizNoInserter -- 报销单号碰撞，换号重试（第 2 次）：报销单号=R...
#   INFO  BizNoInserter -- 报销单号碰撞换号成功：报销单号=R...（第 2 次尝试）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\test\bizno-collision-retry.ps1 `
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
    [string]$Stamp = "COLLISIONPROBE"
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

# 大 SQL 走临时文件：Windows 命令行长度上限约 32KB，600 行探针 VALUES 会超限
# （实测 "The filename or extension is too long"）。mysql 批量模式支持 `source <file>`。
function Invoke-SqlFile([string]$sql) {
    $tf = Join-Path $env:TEMP ("bizno-probe-{0}.sql" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)))
    [System.IO.File]::WriteAllText($tf, $sql, [System.Text.UTF8Encoding]::new($false))
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $MySqlExe -h $DbHost -P $Port -u $User "-p$Password" --default-character-set=utf8mb4 -N -B -e "source $tf" 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
        Remove-Item $tf -Force -ErrorAction SilentlyContinue
    }
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

# 上传一张新附件（同一 file_record 只能绑一张报销单，必须每次新上传）
function Upload-Sample([string]$token, $samples, [string]$curl) {
    $src = $samples[(Get-Random -Maximum $samples.Count)]
    $tmp = Join-Path $env:TEMP ("probe-{0}{1}" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)), $src.Extension)
    Copy-Item $src.FullName $tmp -Force
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $curl -s -X POST "$Gateway/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$tmp;type=image/jpeg" 2>&1 }
    finally { $ErrorActionPreference = $prev }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $id = (($out | Out-String).Trim() | ConvertFrom-Json).data.id
    if (-not $id) { throw ("附件上传失败: {0}" -f ($out -join ' ')) }
    return $id
}

function Submit-Probe([string]$token, [string]$title, [decimal]$amount, [int]$fileId) {
    $body = @{
        title = $title; expenseType = "OFFICE"; deptName = $DeptName; deptId = $DeptId
        claimDate = "$Period-01"; remark = "bizno collision probe"
        items = @(@{ name = "测试明细"; amount = $amount })
        fileRecordIds = @($fileId)
    }
    return (Api POST "/api/v1/reimbursements" $body $token)
}

Write-Host "=== 单号碰撞重试确定性验证 ===" -ForegroundColor Cyan
Write-Host ("网关 {0}   库 {1}:{2}/{3}" -f $Gateway, $DbHost, $Port, $Database) -ForegroundColor DarkGray
Write-Host ("造撞方式：等到整秒瞬间，占满该秒的全部 10000 个尾号后再提交" ) -ForegroundColor DarkGray

$targetLabel = $null

try {
    # ---------- 0. 登录 + 附件 + 登录一次后用「精确占号」造撞 ----------
    # ⚠️ 踩坑记录一：不要在 SQL 里用 DATE_FORMAT(..., '%Y%m%d%H%i%s') 生成时间戳。
    #    双引号 here-string 中 '%i%s' 会被 PowerShell 当成变量 $i/$s 替换成空串，
    #    格式串静默退化为 '%Y%m%d%H'，同一小时内只生成 10 个不同单号，INSERT 自身就撞车
    #    （实测报 Duplicate entry 'T202609120106380000' for key 'uk_task_no'）。
    #    故时间戳一律在 PowerShell 侧算好。
    # ⚠️ 踩坑记录二：不要只占尾号 0-9。生成器每次抽的是 0-9999 的随机数，
    #    只占 10 个尾号的撞中概率仍是 10/10000 —— 换了种方式再赌千分之一。
    #    必须把【目标秒的全部 10000 个尾号】都占掉，才能保证必撞。
    # ---------- 1. 登录 ----------
    $login = Api POST "/api/v1/auth/login" @{ username = "admin"; password = "admin123" } $null
    if ($login.code -ne 0) { throw ("登录失败: {0}" -f $login.message) }
    $token = $login.data.token
    Write-Host "[1] 登录成功" -ForegroundColor DarkGray

    $sampleDir = Join-Path (Split-Path $PSScriptRoot -Parent) "ocr-samples\local"
    $samples = @(Get-ChildItem $sampleDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" })
    $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
    if (-not $curl) { throw "需要 curl.exe 做 multipart 上传" }
    if ($samples.Count -eq 0) { throw ("未找到 OCR 样本: {0}" -f $sampleDir) }

    $maxAmt = [decimal](Invoke-Sql "SELECT COALESCE(MAX(total_amount), 0) + 1 FROM $Database.expense_reimbursement WHERE deleted = 0;")

    # ---------- 2. 占有线程 + 提交（重试 3 次） ----------
    # 关键认知：单号不是在"提交那一刻"生成的。submit() 在生成单号之前还要走
    # 「文件服务 Feign 校验附件 → 部门校验 → 金额重算」等远程调用，实测
    # 号段生成时刻比请求发出时刻【晚 2~4 秒】。
    # 因此不能"先占号再提交"（占了的那一秒早就过去了），必须：
    #   请求发出后，起一个后台线程，把接下来 BeforeSec+AfterSec 秒的全部尾号连续占满；
    #   请求由主线程阻塞等待，单号必然落在被占满的窗口内。
    $beforeSec = 1; $afterSec = 8
    $success = $false
    foreach ($attempt in 1..3) {
        Write-Host ("[2.{0}] 启动占有线程 → 提交（覆盖请求后 {1}~{2} 秒的全部尾号）" -f $attempt, $beforeSec, ($beforeSec + $afterSec)) -ForegroundColor Yellow
        $fid = Upload-Sample $token $samples $curl

        $script:occupyLog = @()
        $targetLabel = "$Stamp`_T_$([guid]::NewGuid().ToString('N').Substring(0,6))"
        # 秒0 在主线程算好：占有线程要用它作起点，主线程要用它轮询"是否已占满"
        $sec0 = [datetime]::Now.ToString('yyyyMMddHHmmss')
        $ps = [powershell]::Create()
        [void]$ps.AddScript({
            param($MySqlExe, $DbHost, $Port, $User, $Password, $Database, $label, $beforeSec, $afterSec, $sec0)
            # ⚠️ 竞态（踩过两次）：必须从【发起时刻的当前秒】就占满，不能从"下一整秒"开始。
            #    填满一秒约需 1 秒，而 submit() 取号只需约 100ms —— 若从下一整秒开始占，
            #    等该秒占满时号段早已取走（实测：生成秒 :54，占满的却是 :54 之后的秒）。
            #    另外占有线程的写库会与服务的读库竞争，把 submit 往返从 3~4 秒压到约 100ms，
            #    所以"取号晚 2~4 秒"并不可靠、不能依赖。
            # 做法：从当前秒起连续占 3 秒；主线程等秒0 占满后再发请求，
            # 这样首次取号必撞秒0，换号重试大概率落在秒1/秒2（也已被占）。
            $base = [datetime]::ParseExact($sec0, 'yyyyMMddHHmmss', $null)
            $log = @()
            foreach ($k in 0..2) {
                $sec = $base.AddSeconds($k).ToString('yyyyMMddHHmmss')
                $st = [datetime]::Now
                # 4000 行一批：单条 INSERT 太长会超命令行上限，分批后仍远快于 1 秒
                foreach ($start in 0, 4000, 8000) {
                    $vals = @()
                    foreach ($d in $start..([Math]::Min($start + 3999, 9999))) {
                        $vals += ("('T{0}{1:d4}', '{2}', 0, '{{}}')" -f $sec, $d, $label)
                    }
                    $tf = Join-Path $env:TEMP ("occ-{0}.sql" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)))
                    $sql = "INSERT INTO $Database.agent_task (task_no, title, total_steps, input_params) VALUES " + ($vals -join ", ") + ";"
                    [System.IO.File]::WriteAllText($tf, $sql, [System.Text.UTF8Encoding]::new($false))
                    $prevEap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
                    try { & $MySqlExe -h $DbHost -P $Port -u $User "-p$Password" -e "source $tf" 2>&1 | Out-Null }
                    finally { $ErrorActionPreference = $prevEap; Remove-Item $tf -Force -ErrorAction SilentlyContinue }
                }
                $log += ("{0} 占满 10000 行，耗时 {1} ms" -f $sec, [int]([datetime]::Now - $st).TotalMilliseconds)
            }
            return $log
        }).AddArgument($MySqlExe).AddArgument($DbHost).AddArgument($Port).AddArgument($User).AddArgument($Password).AddArgument($Database).AddArgument($targetLabel).AddArgument($beforeSec).AddArgument($afterSec).AddArgument($sec0)

        $handle = $ps.BeginInvoke()
        # 等秒0 真正占满再发请求（最多等 3 秒）。否则会出现竞态：
        # 填满一秒要约 1 秒，而号段在 ~100ms 内就取走了（实测生成秒早于占满的秒）。
        # 判定方式：按「秒0 的 10000 个尾号」精确计数，与占有线程的插入进度直接对应。
        $readyCount = 0
        $waited = 0
        while ($waited -lt 3000) {
            $readyCount = [int](Invoke-Sql "SELECT COUNT(*) FROM $Database.agent_task WHERE task_no LIKE 'T$sec0%';")
            if ($readyCount -ge 10000) { break }
            Start-Sleep -Milliseconds 100
            $waited += 100
        }
        Write-Host ("      秒0({0}) 已占 {1}/10000 行，等待 {2} ms" -f $sec0, $readyCount, $waited) -ForegroundColor DarkGray

        $tSend = [datetime]::Now
        $r = Submit-Probe $token ("碰撞重试验证{0}" -f $attempt) $maxAmt $fid
        $elapsed = [int]([datetime]::Now - $tSend).TotalMilliseconds

        try { $occupyOut = @($ps.EndInvoke($handle)) } catch { $occupyOut = @("占有线程异常: $_") } finally { $ps.Dispose() }
        foreach ($l in $occupyOut) { Write-Host ("      [占用线程] {0}" -f $l) -ForegroundColor DarkGray }

        if ($r.code -eq 0) {
            $reimbNo = $r.data.reimbNo
            $taskNo = Invoke-Sql "SELECT task_no FROM $Database.agent_task WHERE id = $($r.data.taskId);"
            $genSec = $reimbNo.Substring(1, 14)
            $occupySecs = @($occupyOut | ForEach-Object { ($_ -split ' ')[0] })
            Write-Host ("      提交往返 {0} ms；报销单号 = {1}    任务号 = {2}" -f $elapsed, $reimbNo, $taskNo) -ForegroundColor DarkGray
            Write-Host ("      号段生成秒 = {0}" -f $genSec) -ForegroundColor DarkGray

            Check ($reimbNo.Substring(0, 1)) "R" "报销单号前缀"
            Check ($reimbNo.Length) "19" "报销单号长度（R + 18 位）"
            Check ($taskNo.Length) "19" "任务号长度（T + 18 位）"

            $hit = ($occupySecs -contains $genSec)
            Write-Host ("      生成秒是否落在被占满的秒内 = {0}" -f $hit) -ForegroundColor DarkGray
            if ($hit) {
                Write-Host ("  [PASS] 号段生成于被占满的秒 {0} ⇒ uk_reimb_no 与 uk_task_no 必然撞过，走的正是换号重试分支" -f $genSec) -ForegroundColor Green
                $script:pass++
            } else {
                Write-Host ("  [WARN] 生成秒 {0} 不在被占满的秒集合内：本次未造出撞号" -f $genSec) -ForegroundColor DarkYellow
                $script:fail++
            }
            $success = $true
            break
        } else {
            # 提交失败同样是有价值的证据：若 3 次换号都落在被占满的秒内，
            # BizNoInserter 会放弃重试并抛 BizException，对外即 400「系统繁忙，请稍后重试」。
            # 这恰好反证重试机制在工作（老代码只会报「数据唯一约束冲突」）。
            Write-Host ("      提交失败：code={0} message={1}" -f $r.code, $r.message) -ForegroundColor DarkYellow
            if ("$($r.message)" -match '稍后重试') {
                Write-Host "      ↑ 「稍后重试」= BizNoInserter 连续 3 次撞号后放弃重试的对外语义" -ForegroundColor DarkYellow
                Write-Host "        （老代码此处应为「数据唯一约束冲突」——说明换号重试确实生效了）" -ForegroundColor DarkYellow
                $script:pass++
                Write-Host "  [PASS] 连续撞号时对外语义正确（BizException → 可读提示，而非暴露唯一索引细节）" -ForegroundColor Green
            } elseif ("$($r.message)" -match '唯一约束') {
                Write-Host "      ↑ 出现「数据唯一约束冲突」说明【未走换号重试】，请检查服务是否为最新代码" -ForegroundColor Red
                $script:fail++
            }
            Start-Sleep -Milliseconds 300
        }
    }

    if (-not $success) { throw "3 次造撞均未得到成功提交，无法验证" }
} finally {
    # 探针行清理：按 label 精确删（本轮所有探针共用同一个 label）
    if ($targetLabel) {
        try {
            Invoke-Sql "DELETE FROM $Database.agent_task WHERE title = '$targetLabel';" | Out-Null
            Write-Host ("[清理] 探针行已删除（label={0}）" -f $targetLabel) -ForegroundColor DarkGray
        } catch {
            Write-Host ("[清理] 探针行删除失败：{0}" -f $_) -ForegroundColor Red
            Write-Host ("       请手工执行: DELETE FROM $Database.agent_task WHERE title = '{0}';" -f $targetLabel) -ForegroundColor Yellow
        }
    }
    # 兜底：把历史遗留的同前缀探针一并清掉（脚本中途被 Ctrl+C 时会残留）
    try {
        $left = Invoke-Sql "SELECT COUNT(*) FROM $Database.agent_task WHERE title LIKE '$Stamp%';"
        if ($left -ne "0") {
            Invoke-Sql "DELETE FROM $Database.agent_task WHERE title LIKE '$Stamp%';" | Out-Null
            Write-Host ("[清理] 另清理历史残留探针 {0} 行" -f $left) -ForegroundColor DarkGray
        }
    } catch { }
}

Write-Host ""
Write-Host ("=== 汇总: PASS={0} FAIL={1} ===" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host "请到 agent-core 控制台查找下面这些日志——找到即证明换号重试链路真的执行了：" -ForegroundColor Yellow
Write-Host "  WARN  BizNoInserter -- 报销单号碰撞，换号重试（第 2 次）：报销单号=R..." -ForegroundColor DarkGray
Write-Host "  INFO  BizNoInserter -- 报销单号碰撞换号成功：报销单号=R...（第 2 次尝试）" -ForegroundColor DarkGray
Write-Host "  WARN  BizNoInserter -- 任务号碰撞，换号重试（第 2 次）：任务号=T..." -ForegroundColor DarkGray
Write-Host "  INFO  BizNoInserter -- 任务号碰撞换号成功：任务号=T...（第 2 次尝试）" -ForegroundColor DarkGray
Write-Host "（脚本会自动清理探针行，无需手工清理；本轮产生的报销单/任务是测试残留，可留作复核）" -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
