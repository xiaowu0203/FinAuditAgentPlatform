# =====================================================================
# 预算占用并发验证脚本（P3.8 R1）
#
# 目的：证明「超支拦截由单条原子 UPDATE 在数据库层保证」，而非应用层"先查再算再写"。
#       直接对 MySQL 并发执行与 BudgetMapper.occupy 完全等价的 SQL。
#
# 用法（在项目根目录）：
#   pwsh -File docs/test/budget-occupancy-concurrency.ps1
#   pwsh -File docs/test/budget-occupancy-concurrency.ps1 -MySqlExe "mysql" -User root -Password root
#
# 判定标准：
#   场景1  预算 10000 / 20 并发各占 600  → 成功 16、失败 4、used_amount = 9600
#   场景2  预算 10000 / 20 并发各占 6000 → 成功 1、失败 19、used_amount = 6000（**核心用例**）
#   场景3  释放 600 → used_amount 减 600，且不会变负
# =====================================================================

param(
    [string]$MySqlExe = "mysql",
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [int]$Concurrency = 20
)

$ErrorActionPreference = "Stop"
$script:failed = $false

function Invoke-Sql([string]$sql) {
    # 注意：Windows PowerShell 5.1 把原生命令的 stderr 包成 ErrorRecord，
    # 在 $ErrorActionPreference='Stop' 下会直接终止脚本（mysql 的密码告警就会触发）。
    # 故此处临时切回 Continue，改为按退出码判定成败，并剔除 mysql 告警行。
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $MySqlExe -h $DbHost -P $Port -u $User "-p$Password" --default-character-set=utf8mb4 `
            -N -B -e $sql 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    $lines = @($raw | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'Using a password' })
    if ($code -ne 0) {
        throw "SQL 执行失败($code): $($lines -join ' / ')`nSQL: $sql"
    }
    return ($lines -join "`n").Trim()
}

function Assert-Equal($actual, $expected, [string]$label) {
    if ("$actual" -eq "$expected") {
        Write-Host ("  [PASS] {0}: {1}" -f $label, $actual) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] {0}: 实际 {1}，期望 {2}" -f $label, $actual, $expected) -ForegroundColor Red
        $script:failed = $true
    }
}

Write-Host "=== 预算占用并发验证（R1）===" -ForegroundColor Cyan
Write-Host "目标: $DbHost`:$Port/$Database  并发度: $Concurrency"

# 0. 前置：表存在
$tbl = Invoke-Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='budget_occupancy';"
if ($tbl -ne "1") {
    Write-Host "budget_occupancy 不存在，请先执行 docs/database/migration-P3.8.sql" -ForegroundColor Red
    exit 1
}

# 测试用沙箱数据（tenant_id=9999 避免污染真实数据）
$sandbox = @"
DELETE FROM $Database.budget WHERE tenant_id = 9999;
DELETE FROM $Database.budget_occupancy WHERE tenant_id = 9999;
INSERT INTO $Database.budget (tenant_id, dept_name, dept_id, period, total_budget, used_amount, deleted)
VALUES (9999, '并发测试部', 9901, '2099-01', 10000.00, 0.00, 0);
"@
Invoke-Sql $sandbox | Out-Null

# 并发执行与 BudgetMapper.occupy 完全等价的原子 SQL（超支条件在 WHERE 里），返回成功笔数
function Run-Concurrent([int]$amount, [int]$n) {
    $jobs = 1..$n | ForEach-Object {
        Start-Job -ScriptBlock {
            param($exe, $h, $p, $u, $pw, $db, $amt)
            $sql = "UPDATE $db.budget SET used_amount = used_amount + $amt " +
                   "WHERE tenant_id = 9999 AND dept_id = 9901 AND period = '2099-01' " +
                   "AND deleted = 0 AND used_amount + $amt <= total_budget; SELECT ROW_COUNT();"
            $prev = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $r = & $exe -h $h -P $p -u $u "-p$pw" -N -B -e $sql 2>&1
            } finally {
                $ErrorActionPreference = $prev
            }
            @($r | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'Using a password' }) -join "`n"
        } -ArgumentList $MySqlExe, $DbHost, $Port, $User, $Password, $Database, $amount
    }
    $rows = $jobs | Wait-Job | Receive-Job
    $jobs | Remove-Job -Force
    return @($rows | Where-Object { $_.Trim() -eq "1" }).Count
}

# 场景1：预算 10000，20 并发各占 600 → 业务不变量：
#   used_amount 不得超过 total_budget，且必须等于「成功笔数 × 600」（无丢失更新）
Write-Host "`n--- 场景1：20 并发 × 600（预算 10000）---" -ForegroundColor Yellow
Invoke-Sql "UPDATE $Database.budget SET used_amount = 0.00 WHERE tenant_id = 9999;" | Out-Null
$ok1 = Run-Concurrent 600 $Concurrency
$used1 = Invoke-Sql "SELECT used_amount FROM $Database.budget WHERE tenant_id = 9999;"
$expected1 = [decimal]$ok1 * 600
Assert-Equal $used1 ("{0:F2}" -f $expected1) "used_amount 等于成功笔数×600（无丢失更新）"
$over1 = [decimal]$used1 -gt 10000
Assert-Equal $over1 $false "used_amount 未超过 total_budget"
Assert-Equal $ok1 16 "成功笔数（10000/600 向下取整）"

# 场景2（核心）：预算 10000，20 并发各占 6000 → 只允许 1 笔成功
# 这是「先查再算再写」必然失败、而原子 UPDATE 必然通过的判据：
# 若实现为应用层读改写，20 个线程会读到同一个 used_amount=0 而全部判定"充足"，最终 used_amount=120000。
Write-Host "`n--- 场景2（核心）：20 并发 × 6000（预算 10000）应仅 1 笔成功 ---" -ForegroundColor Yellow
Invoke-Sql "UPDATE $Database.budget SET used_amount = 0.00 WHERE tenant_id = 9999;" | Out-Null
$ok2 = Run-Concurrent 6000 $Concurrency
$used2 = Invoke-Sql "SELECT used_amount FROM $Database.budget WHERE tenant_id = 9999;"
Assert-Equal $ok2 1 "成功笔数"
Assert-Equal $used2 "6000.00" "used_amount（不得超 total_budget=10000）"

# 场景3：释放不回负
Write-Host "`n--- 场景3：释放 600（GREATEST 防负数）---" -ForegroundColor Yellow
Invoke-Sql "UPDATE $Database.budget SET used_amount = GREATEST(used_amount - 600.00, 0) WHERE tenant_id = 9999;" | Out-Null
$used3 = Invoke-Sql "SELECT used_amount FROM $Database.budget WHERE tenant_id = 9999;"
Assert-Equal $used3 "5400.00" "used_amount"
Invoke-Sql "UPDATE $Database.budget SET used_amount = GREATEST(used_amount - 99999.00, 0) WHERE tenant_id = 9999;" | Out-Null
$used4 = Invoke-Sql "SELECT used_amount FROM $Database.budget WHERE tenant_id = 9999;"
Assert-Equal $used4 "0.00" "超额释放后 used_amount（不得为负）"

# 清理沙箱数据
Invoke-Sql "DELETE FROM $Database.budget WHERE tenant_id = 9999; DELETE FROM $Database.budget_occupancy WHERE tenant_id = 9999;" | Out-Null
Write-Host "`n沙箱数据已清理（tenant_id=9999）" -ForegroundColor DarkGray

if ($script:failed) {
    Write-Host "`n== 存在失败项 ==" -ForegroundColor Red
    exit 1
}
Write-Host "`n== 全部通过：超支由数据库层原子 UPDATE 拦死 ==" -ForegroundColor Green
exit 0
