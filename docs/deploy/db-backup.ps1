# =====================================================================
# 数据库备份脚本（P3.8 R9 事故后补：动数据前先留一份）
#
# 背景：本仓所有 SQL 脚本头部都有 `USE finaudit;`，且全量 schema 会 DROP 全部表；
#       本机 MySQL 未开 binlog —— 一次误执行就**无从恢复**（实测清空过一次本地库）。
#       本脚本提供一条命令的 mysqldump 备份，作为任何 DDL/迁移操作的前置动作。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs\deploy\db-backup.ps1 `
#       -MySqlDumpExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysqldump.exe" `
#       -User root -Password root
#
# 输出：backups\finaudit-<yyyyMMdd-HHmmss>.sql（backups/ 已在 .gitignore 中）
# =====================================================================

param(
    [string]$MySqlDumpExe = "mysqldump",
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "root",
    [string]$Database = "finaudit",
    [string]$OutDir = "",
    # 是否在备份前后打印关键表行数（便于对照恢复是否完整）
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"

if (-not $OutDir) {
    # 默认放在仓库根的 backups/（已 gitignore），避免误入版本库
    $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $OutDir = Join-Path $repoRoot "backups"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outFile = Join-Path $OutDir ("{0}-{1}.sql" -f $Database, $stamp)
if (Test-Path $outFile) { throw "备份文件已存在: $outFile" }

# 备份前记录关键行数（恢复后可比对）
function RowCounts([string]$db) {
    $sql = "SELECT CONCAT('users=', (SELECT COUNT(*) FROM sys_user), ' tasks=', (SELECT COUNT(*) FROM agent_task), " +
           "' reimbs=', (SELECT COUNT(*) FROM expense_reimbursement), ' tickets=', (SELECT COUNT(*) FROM audit_ticket), " +
           "' steps=', (SELECT COUNT(*) FROM agent_task_step), ' logs=', (SELECT COUNT(*) FROM model_call_log));"
    $mysql = $MySqlDumpExe -replace 'mysqldump', 'mysql'
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $v = & $mysql -h $DbHost -P $Port -u $User "-p$Password" -N -B -D $db -e $sql 2>&1 } finally { $ErrorActionPreference = $prev }
    return (($v | Where-Object { "$_" -notmatch 'Using a password' }) -join '').Trim()
}

if (-not $Quiet) {
    try { Write-Host ("备份前行数: {0}" -f (RowCounts $Database)) -ForegroundColor DarkGray } catch { }
}

Write-Host ("开始备份 {0} → {1}" -f $Database, $outFile) -ForegroundColor Cyan
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
try {
    # --single-transaction：InnoDB 一致性快照，不锁表
    # --routines --triggers：把可能存在的存储程序一并带上（当前没有，但备着无害）
    # --default-character-set=utf8mb4：避免中文注释/数据变成乱码（本仓踩过编码坑）
    & $MySqlDumpExe -h $DbHost -P $Port -u $User "-p$Password" `
        --single-transaction --routines --triggers --default-character-set=utf8mb4 `
        --databases $Database 2>&1 | Out-File -FilePath $outFile -Encoding utf8
    $code = $LASTEXITCODE
} finally { $ErrorActionPreference = $prev }

if ($code -ne 0) { throw ("mysqldump 失败（exit={0}），备份文件不可信: {1}" -f $code, $outFile) }

$size = (Get-Item $outFile).Length
Write-Host ("✅ 备份完成: {0}（{1:N1} KB）" -f $outFile, ($size / 1KB)) -ForegroundColor Green
Write-Host "恢复方式（覆盖式，务必确认目标库）:" -ForegroundColor DarkGray
Write-Host ("  mysql -u{0} -p < `"{1}`"" -f $User, $outFile) -ForegroundColor DarkGray
if ($size -lt 1024) { Write-Host "⚠️ 备份文件异常小，请人工确认内容是否完整" -ForegroundColor DarkYellow }
