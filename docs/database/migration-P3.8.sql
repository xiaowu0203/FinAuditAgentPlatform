-- =====================================================================
-- P3.8 增量迁移（R1：预算真实占用与释放）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3.8.sql
--
-- 背景（业务走查 B-1/B-2）：
--   budget.used_amount 此前**全仓无写入点**——注释写着"审核通过后累加（P3 审批流）"但从未实现，
--   系统只做只读预检、从不扣减。后果：同一部门同月多笔报销全部报"预算充足"、
--   全部可 AUTO_PASS/审批通过，系统一次都不拦，"预算管控"是空话。
--
-- 内容:
--   ① 新增 budget_occupancy 占用记账表：记录每张报销单的预算占用/释放，支撑
--      「占用-释放配平」对账（SUM(OCCUPIED)-SUM(RELEASED) 应等于 budget.used_amount）
--   ② budget.used_amount 语义变更：由「只读种子值」变为「真实累加值」（无需 DDL，仅语义）
--
-- 幂等: CREATE TABLE IF NOT EXISTS；可重复执行
-- =====================================================================

USE finaudit;

-- ---------------------------------------------------------------------
-- 1. 预算占用记账表
--    一个报销单至多一条记录（uk_reimb），因其生命周期内占用/释放成对发生；
--    occupy_count / release_count 记录发生次数（resubmit 重跑会再次占用），
--    配平公式：SUM(amount WHERE OCCUPIED) - SUM(amount WHERE RELEASED) == used_amount
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_occupancy (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    reimb_id      BIGINT        NOT NULL COMMENT '报销单ID（一单一记录，业务幂等键）',
    task_id       BIGINT        DEFAULT NULL COMMENT '关联审核任务ID（追溯用）',
    dept_id       BIGINT        NOT NULL COMMENT '部门ID（budget 权威关联键，P3.5b）',
    period        VARCHAR(7)    NOT NULL COMMENT '预算周期 YYYY-MM（按报销日期推导）',
    amount        DECIMAL(12,2) NOT NULL COMMENT '占用金额（Decimal 强制）',
    status        VARCHAR(16)   NOT NULL DEFAULT 'OCCUPIED' COMMENT '占用状态: OCCUPIED 已占用 / RELEASED 已释放',
    occupy_count  INT           NOT NULL DEFAULT 1 COMMENT '累计占用次数（resubmit 重跑会累加）',
    release_count INT           NOT NULL DEFAULT 0 COMMENT '累计释放次数',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reimb (tenant_id, reimb_id) COMMENT '一单一记录，防重复占用',
    KEY idx_dept_period (tenant_id, dept_id, period),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '预算占用记账表（P3.8 R1；支撑占用-释放配平对账）';

-- ---------------------------------------------------------------------
-- 2. 核对：现有预算行（used_amount 语义自此开始真实累加）
-- ---------------------------------------------------------------------
SELECT b.id, b.tenant_id, b.dept_id, b.dept_name, b.period,
       b.total_budget, b.used_amount,
       (SELECT COUNT(*) FROM budget_occupancy o
         WHERE o.tenant_id = b.tenant_id AND o.dept_id = b.dept_id
           AND o.period = b.period AND o.deleted = 0) AS occupancy_rows
FROM budget b
WHERE b.deleted = 0
ORDER BY b.tenant_id, b.dept_id, b.period;
