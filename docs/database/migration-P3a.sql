-- =====================================================================
-- P3a 增量迁移（仅一次；本机已有 P2c 数据时执行，勿与 finaidit-schema.sql 重灌混用）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3a.sql
-- 注意: MySQL 5.7 的 ALTER ADD COLUMN 不支持 IF NOT EXISTS，
--       下方 ALTER 语句若已存在 agent_role 列会报错（重复执行时忽略即可）。
-- =====================================================================

USE finaudit;

-- 1. agent_task_step 加执行角色列（P3a 角色化流水线：步骤绑定 AgentRole）
ALTER TABLE agent_task_step
    ADD COLUMN agent_role VARCHAR(32) DEFAULT NULL COMMENT '执行角色（AgentRole: SCHEDULER/DOCUMENT_PARSER/BUDGET_CALCULATOR/RULE_VALIDATOR/RISK_AUDITOR；历史与 GENERIC 步骤为空）' AFTER tool_name;
