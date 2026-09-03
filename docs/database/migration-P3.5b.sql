-- =====================================================================
-- P3.5b 增量迁移（部门实体落地；本机已有 P3.5a 数据时执行，勿与 finaudit-schema.sql 重灌混用）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3.5b.sql
-- 内容: ① sys_dept 部门表（租户内树形，uk(tenant_id,dept_name)）
--       ② 部门字典回填源：reimb ∪ budget 现存 dept_name（当前 5 部门无脏名异写；
--          未来同名异写由 uk 兜底，合并前先人工核对——回填原则见 P3.5-execution-plan §5 R2）
--       ③ sys_user.dept_id 加列 + 种子用户绑定（admin→财务部 / emp1→研发部 / emp2→研发部）
--       ④ expense_reimbursement.dept_id 加列 + 按同租户 dept_name 精确匹配回填（dept_name 保留为提交时快照）
--       ⑤ budget.dept_id 加列 + 回填 + 置 NOT NULL + 唯一键切换(tenant_id,dept_id,period)
-- 幂等: CREATE TABLE IF NOT EXISTS / INSERT IGNORE；ALTER 语句用 information_schema 存在性判断包裹
-- =====================================================================

USE finaudit;

-- ---------------------------------------------------------------------
-- 1. 部门表（租户内；parent_id=0 代表根；dept_name 租户内唯一——1:1 关联键）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_dept (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT      NOT NULL DEFAULT 1 COMMENT '租户ID',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父部门ID（0=根）',
    dept_name   VARCHAR(64) NOT NULL COMMENT '部门名称',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0停用',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_dept (tenant_id, dept_name),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '部门表（租户内树形；删除受子部门/用户引用约束）';

-- ---------------------------------------------------------------------
-- 2. 部门字典回填源：现存 expense_reimbursement ∪ budget 的 dept_name 建根部门
--    （INSERT IGNORE + uk 幂等；新出现的 dept_name 会在重跑时增量补齐）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO sys_dept (tenant_id, parent_id, dept_name)
SELECT DISTINCT tenant_id, 0, dept_name FROM expense_reimbursement
UNION
SELECT DISTINCT tenant_id, 0, dept_name FROM budget;

-- ---------------------------------------------------------------------
-- 3. sys_user.dept_id 加列 + 种子用户绑定（员工级部门归属；账户级绑定，R3 UI 可改绑）
-- ---------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'finaudit' AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'dept_id');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN dept_id BIGINT NULL COMMENT ''部门ID（员工级归属，P3.5b）'' AFTER phone',
    'SELECT ''sys_user.dept_id 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 种子用户绑定（仅当未绑定；用户名映射自 P2 种子数据，R3 用户管理页可改）
UPDATE sys_user SET dept_id = (SELECT id FROM sys_dept WHERE tenant_id = 1 AND dept_name = '财务部')
 WHERE tenant_id = 1 AND username = 'admin' AND dept_id IS NULL;
UPDATE sys_user SET dept_id = (SELECT id FROM sys_dept WHERE tenant_id = 1 AND dept_name = '研发部')
 WHERE tenant_id = 1 AND username IN ('emp1', 'emp2') AND dept_id IS NULL;

-- ---------------------------------------------------------------------
-- 4. expense_reimbursement.dept_id 加列 + 回填（dept_name 保留为提交时快照，不作主数据）
-- ---------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'finaudit' AND TABLE_NAME = 'expense_reimbursement' AND COLUMN_NAME = 'dept_id');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE expense_reimbursement ADD COLUMN dept_id BIGINT NULL COMMENT ''提交者部门ID（P3.5b；dept_name 为提交时快照）'' AFTER dept_name',
    'SELECT ''expense_reimbursement.dept_id 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填：同租户 dept_name 精确匹配 sys_dept（匹配不到留 NULL——结构校验对 null 跳过）
UPDATE expense_reimbursement r JOIN sys_dept d ON d.tenant_id = r.tenant_id AND d.dept_name = r.dept_name
SET r.dept_id = d.id
WHERE r.dept_id IS NULL;

-- ---------------------------------------------------------------------
-- 5. budget.dept_id 加列 + 回填 + 置 NOT NULL + 唯一键切换(tenant_id,dept_id,period)
-- ---------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'finaudit' AND TABLE_NAME = 'budget' AND COLUMN_NAME = 'dept_id');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE budget ADD COLUMN dept_id BIGINT NULL COMMENT ''部门ID（P3.5b 权威关联键；dept_name 冗余仅显示）'' AFTER dept_name',
    'SELECT ''budget.dept_id 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填（单事务 DML 段）
START TRANSACTION;
UPDATE budget b JOIN sys_dept d ON d.tenant_id = b.tenant_id AND d.dept_name = b.dept_name
SET b.dept_id = d.id
WHERE b.dept_id IS NULL;
-- 回填完整性核对：存在 NULL dept_id 则中止，避免置 NOT NULL 报错留半状态
SELECT COUNT(*) AS budget_null_dept_id FROM budget WHERE dept_id IS NULL;
COMMIT;

SET @null_count = (SELECT COUNT(*) FROM budget WHERE dept_id IS NULL);
SET @sql = IF(@null_count = 0,
    'ALTER TABLE budget MODIFY COLUMN dept_id BIGINT NOT NULL COMMENT ''部门ID（P3.5b 权威关联键）''',
    'SELECT ''budget.dept_id 仍有 NULL，未置 NOT NULL（需人工核对 dept_name）''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 唯一键切换：删旧(tenant_id,dept_name,period) → 加新(tenant_id,dept_id,period)
-- 检测注意：uk 首列是 tenant_id（seq=1），dept_name 是第二列——须按"键内是否含 dept_name 列"判定新旧
SET @old_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'finaudit' AND TABLE_NAME = 'budget' AND INDEX_NAME = 'uk_dept_period'
      AND COLUMN_NAME = 'dept_name');
SET @sql = IF(@old_key > 0, 'ALTER TABLE budget DROP KEY uk_dept_period', 'SELECT ''旧 uk_dept_period 已移除''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @new_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'finaudit' AND TABLE_NAME = 'budget' AND INDEX_NAME = 'uk_dept_period'
      AND COLUMN_NAME = 'dept_id');
SET @sql = IF(@new_key = 0,
    'ALTER TABLE budget ADD UNIQUE KEY uk_dept_period (tenant_id, dept_id, period)',
    'SELECT ''新 uk_dept_period(tenant_id,dept_id,period) 已存在''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 6. 回填报告（留痕核对）
-- ---------------------------------------------------------------------
SELECT 'sys_dept' AS t, COUNT(*) AS cnt FROM sys_dept
UNION ALL SELECT 'reimb dept_id 回填', COUNT(*) FROM expense_reimbursement WHERE dept_id IS NOT NULL
UNION ALL SELECT 'reimb dept_id 未回填(NULL)', COUNT(*) FROM expense_reimbursement WHERE dept_id IS NULL
UNION ALL SELECT 'budget dept_id 回填', COUNT(*) FROM budget WHERE dept_id IS NOT NULL;