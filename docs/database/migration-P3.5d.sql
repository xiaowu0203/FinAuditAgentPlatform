-- =====================================================================
-- P3.5d 增量迁移（可靠性与安全加固）：agent_task 本次执行开始时间
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3.5d.sql
-- 内容: ① agent_task.started_at：启动/修改重跑时刷新，作为任务级超时预算
--         （finaudit.agent.task-timeout-minutes，默认 30 分钟）的计时起点；
--         存量行保持 NULL，编排器回退 created_at 判定
-- 幂等: information_schema 存在性判断包裹 ALTER
-- =====================================================================

USE finaudit;

-- 1. agent_task 增加 started_at（幂等：已存在则跳过）
SET @ddl := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME   = 'agent_task'
           AND COLUMN_NAME  = 'started_at') = 0,
    'ALTER TABLE agent_task ADD COLUMN started_at DATETIME DEFAULT NULL COMMENT ''本次执行开始时间（启动/修改重跑刷新；任务级超时预算计时起点，P3.5d）'' AFTER status',
    'SELECT ''agent_task.started_at 已存在，跳过'' AS info')
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 回填：执行中（RUNNING）的存量任务无 started_at 时以 created_at 兜底，
--    使超时预算对升级前已存在的任务同样生效
UPDATE agent_task
SET started_at = created_at
WHERE status = 'RUNNING'
  AND started_at IS NULL;

-- 3. 核对
SELECT status, COUNT(*) AS total,
       SUM(started_at IS NULL) AS no_started_at
FROM agent_task
GROUP BY status;
