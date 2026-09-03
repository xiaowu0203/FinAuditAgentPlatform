-- =====================================================================
-- P3.5c 增量迁移（权限缺口修补）：工具管理/调试权限码 + 文件归属列
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3.5c.sql
-- 内容: ① 新增 tool:manage / tool:execute 权限码（系统管理·API，授 admin）
--       ② file_record.created_by 归属列 + 按 expense_attachment→reimb.applicant_id 回填
--         （历史无主文件记 NULL——直接预览/下载时仅财务可见，保守处理）
-- 幂等: INSERT IGNORE / 存在性判断包裹 ALTER
-- =====================================================================

USE finaudit;

-- 1. 工具管理/调试权限码（系统管理·API；目录尾部追加，不占用业务资源级段）
INSERT IGNORE INTO sys_permission (id, perm_code, perm_name, perm_type, group_name) VALUES
    (16, 'tool:manage',  '工具管理（注册/维护）', 'API', '系统管理'),
    (17, 'tool:execute', '工具调试直调',         'API', '系统管理');

-- 授 admin（role_id=1）全量含新码；auditor 不授（工具调试/注册为管理面）
INSERT IGNORE INTO sys_role_permission (tenant_id, role_id, perm_id) VALUES
    (1, 1, 16), (1, 1, 17);

-- 2. file_record.created_by：上传人归属列（直接下载/预览时校验，防租户内按 id 枚举他人附件）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'finaudit' AND TABLE_NAME = 'file_record' AND COLUMN_NAME = 'created_by');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE file_record ADD COLUMN created_by BIGINT NULL COMMENT ''上传人ID（P3.5c 归属校验；历史无主为 null）'' AFTER tenant_id',
    'SELECT ''file_record.created_by 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史回填：附件 → 报销单 → 申请人（同库关联；仅回填仍为 NULL 的行）
UPDATE file_record fr
JOIN expense_attachment ea ON ea.file_record_id = fr.id AND ea.deleted = 0
JOIN expense_reimbursement r ON r.id = ea.reimb_id AND r.deleted = 0
SET fr.created_by = r.applicant_id
WHERE fr.created_by IS NULL;

-- 3. 回填报告
SELECT 'unowned files (finance-only 保守)' AS note, COUNT(*) AS cnt FROM file_record WHERE created_by IS NULL;