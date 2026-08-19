-- =====================================================================
-- P3b 增量迁移（仅一次；本机已有 P3a 数据时执行，勿与 finaudit-schema.sql 重灌混用）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3b.sql
-- =====================================================================

USE finaudit;

-- 1. 审批工单表（P3b 人机协同审批闭环）
--    触发: 任务流水线判定 NEED_REVIEW（复核原因 review_reasons + 触发类型 trigger_type 确定性映射）
--    流转: PENDING -> APPROVED / REJECTED / TERMINATED；AMENDED（改金额回退重跑，可再次复位 PENDING）
--    rerun_count: amend 重跑次数，上限 3（P3 §9 防死循环）
--    audit_level: 审批级数，P3 恒为 1（预留多级审批 TODO P5+）
CREATE TABLE IF NOT EXISTS audit_ticket (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    task_id        BIGINT        NOT NULL COMMENT '关联 agent_task.id',
    ticket_no      VARCHAR(64)   NOT NULL COMMENT '工单编号，如 AT-{taskNo}',
    title          VARCHAR(128)  NOT NULL COMMENT '任务标题（冗余展示）',
    trigger_type   VARCHAR(32)   NOT NULL COMMENT '触发类型: OVER_LIMIT 大额/超标 / RULE_FAIL 规则校验不通过 / RISK_HIT 风控命中（LLM_DECISION 兜底归此类）',
    risk_desc      VARCHAR(512)  DEFAULT NULL COMMENT '复核原因描述（review_reasons join 截断）',
    step_no        INT           DEFAULT NULL COMMENT '触发步骤（预留：决策跨步骤，暂置 NULL）',
    origin_amount  DECIMAL(12,2) DEFAULT NULL COMMENT '申报总额（任务入参 claimedTotal）',
    adjusted_amount DECIMAL(12,2) DEFAULT NULL COMMENT '财务修改后总额（amend 时写，重跑后为最终金额）',
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '工单状态: PENDING/APPROVED/REJECTED/AMENDED/TERMINATED',
    audit_level    TINYINT       NOT NULL DEFAULT 1 COMMENT '审批级数（预留多级审批 TODO P5+，P3 恒 1）',
    rerun_count    INT           NOT NULL DEFAULT 0 COMMENT 'amend 重跑次数，上限 3',
    review_reasons JSON          DEFAULT NULL COMMENT '复核原因列表（JacksonTypeHandler 映射）',
    auditor_id     BIGINT        DEFAULT NULL COMMENT '最近处理人用户ID',
    audit_comment  VARCHAR(512)  DEFAULT NULL COMMENT '最近处理意见',
    created_by     BIGINT        DEFAULT NULL COMMENT '申请人用户ID（任务提交人）',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (tenant_id, ticket_no),
    KEY idx_task (task_id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审批工单表';

-- 2. 审批留痕表（append-only：每次审批动作追加一条，审计溯源）
CREATE TABLE IF NOT EXISTS audit_record (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    ticket_id      BIGINT        NOT NULL COMMENT '工单ID',
    action         VARCHAR(20)   NOT NULL COMMENT '动作: SUBMIT 建单/APPROVE 通过/REJECT 驳回/AMEND 改金额/TERMINATE 终止/RERUN 重跑复位',
    before_amount  DECIMAL(12,2) DEFAULT NULL COMMENT '变更前金额',
    after_amount   DECIMAL(12,2) DEFAULT NULL COMMENT '变更后金额',
    comment        VARCHAR(512)  DEFAULT NULL COMMENT '操作意见',
    operator_id    BIGINT        DEFAULT NULL COMMENT '操作人用户ID',
    operator_name  VARCHAR(64)   DEFAULT NULL COMMENT '操作人姓名',
    operator_roles VARCHAR(128)  DEFAULT NULL COMMENT '操作人当时角色（审计溯源）',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_ticket (ticket_id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审批留痕表（append-only）';

-- =====================================================================
-- 3. 工具入参 schema 修复（P2b/P2c 种子缺陷：可选数值/整数字段 type 声明不允许 null，
--    与 RuleCheckItem 设计「字段可缺省、评估跳过」矛盾——前端未填补助等字段传 null 时
--    rule_check/amount_verify 入参校验直接失败，任务 FAILED。修复为联合类型允许 null。
--    幂等：可重复执行，无副作用。）
-- =====================================================================
UPDATE tool_registry SET input_schema = '{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"amountType":{"type":["string","null"]},"quantity":{"type":["number","null"]},"unitPrice":{"type":["number","null"]},"date":{"type":["string","null"]}},"required":["name","amount"]}},"claimedTotal":{"type":"number"}},"required":["items","claimedTotal"]}'
WHERE tool_code = 'amount_verify';

UPDATE tool_registry SET input_schema = '{"type":"object","properties":{"expenseType":{"type":"string"},"claimDate":{"type":"string"},"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"amountType":{"type":["string","null"]},"quantity":{"type":["number","null"]},"unitPrice":{"type":["number","null"]},"date":{"type":["string","null"]},"city":{"type":["string","null"]},"hotelDays":{"type":["integer","null"]},"hotelAmount":{"type":["number","null"]},"transportAmount":{"type":["number","null"]},"subsidyAmount":{"type":["number","null"]}},"required":["name","amount"]}},"totalAmount":{"type":"number"}},"required":["expenseType","claimDate","items","totalAmount"]}'
WHERE tool_code = 'rule_check';

-- =====================================================================
-- 4. P3b 工作流重设计增量（仅一次；本机已有 P3b 旧工单数据时执行）
--    背景: 真实使用暴露设计问题——重跑模型改为「提交人修改重跑」（同单续跑，
--    rerun_count 上限 3）、新增撤回/撤销申请状态机、每次动作快照留痕。
--    幂等注意: ALTER 加列 / 加唯一键仅可执行一次，重跑会报 Duplicate column/key。
-- =====================================================================

-- 4.1 audit_record 增加前后快照 JSON 列（append-only 审计，快照自包含，可 before→after diff）
ALTER TABLE audit_record
    ADD COLUMN before_data JSON DEFAULT NULL COMMENT '变更前快照（首条 SUBMIT 为 NULL；含 reimb 顶层字段+明细+附件，不含预签名URL/OSS路径）' AFTER after_amount,
    ADD COLUMN after_data  JSON DEFAULT NULL COMMENT '变更后快照（每次动作落一条，审批时点数据现场）' AFTER before_data;

-- 4.2 audit_ticket 加 task 唯一键（同单续跑 1:1 模型：一个 task 至多一个工单；
--     现有 enterApproval 幂等查 selectOne 走该唯一键，杜绝重复建单竞态）
ALTER TABLE audit_ticket
    ADD UNIQUE KEY uk_task (tenant_id, task_id);

-- =====================================================================
-- 5. agent_task_step 软删语义与唯一键解耦（P3b 提交人重跑 replan 撞 uk_task_step 修复；仅一次）
--    背景: replan 先软删（MP 逻辑删写 deleted=1）再以同 (task_id, step_no 1..N) 重插，
--          uk_task_step(task_id, step_no) 不含 deleted → 老行占位 → Duplicate entry。
--    解法: 参照 finance_rule（P2c 同款）：deleted 改 BIGINT、软删置 deleted=该行主键 id、
--          唯一键含 deleted——每行 deleted 不同，历史步骤保留且不占唯一名额。
--    注意: 步骤软删必须走 AgentTaskStepMapper.softDeleteByTaskId（SET deleted=id），
--          禁止再用 MP 默认逻辑删（写 1），否则多轮重跑后同 (task_id, step_no, 1) 再冲突。
--    幂等注意: 仅可执行一次，重跑会报 Duplicate key name / Duplicate column name。
-- =====================================================================

-- 5.1 deleted TINYINT → BIGINT（配合 deleted=id 语义）
ALTER TABLE agent_task_step
    MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0 未删 / 主键id 已删（配合 uk_task_step）';

-- 5.2 存量 deleted=1 的脏行修正为 deleted=id（防历史数据占唯一名额；正常事务回滚后应无此行）
UPDATE agent_task_step SET deleted = id WHERE deleted <> 0;

-- 5.3 唯一键含 deleted（历史重规划行不再冲突）
ALTER TABLE agent_task_step
    DROP INDEX uk_task_step,
    ADD UNIQUE KEY uk_task_step (task_id, step_no, deleted) COMMENT '任务内步骤唯一（含 deleted，重规划历史行不冲突）';
