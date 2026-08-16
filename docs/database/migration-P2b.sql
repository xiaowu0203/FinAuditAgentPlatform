-- =====================================================================
-- P2b 增量迁移（仅一次；本机已有 P2a 数据时执行，勿与 finaidit-schema.sql 重灌混用）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P2b.sql
-- 注意: MySQL 5.7 的 ALTER ADD COLUMN 不支持 IF NOT EXISTS，
--       下方 ALTER 语句若已存在 scenario/cacheable 列会报错（重复执行时忽略即可）；
--       种子数据用 INSERT IGNORE 保证幂等。
-- =====================================================================

USE finaudit;

-- 1. 新表（IF NOT EXISTS 幂等）
CREATE TABLE IF NOT EXISTS budget (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    dept_name    VARCHAR(64)   NOT NULL COMMENT '部门',
    period       VARCHAR(7)    NOT NULL COMMENT '预算周期 YYYY-MM',
    total_budget DECIMAL(14,2) NOT NULL COMMENT '预算总额',
    used_amount  DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '已用额度（审核通过后累加）',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dept_period (tenant_id, dept_name, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '部门预算表';

CREATE TABLE IF NOT EXISTS finance_rule (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    rule_code   VARCHAR(64)   NOT NULL COMMENT '规则编码',
    rule_name   VARCHAR(64)   NOT NULL COMMENT '规则名称',
    rule_type   VARCHAR(32)   NOT NULL COMMENT '规则类型: TRAVEL_STANDARD/SUBSIDY_LIMIT/REIMBURSE_EXPIRE/AMOUNT_LIMIT',
    rule_config JSON          NOT NULL COMMENT '结构化规则（仅存储）',
    enabled     TINYINT       NOT NULL DEFAULT 1 COMMENT '启停: 1启用 0禁用',
    published   TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已发布 Nacos: 1已发布 0未发布',
    version     VARCHAR(16)   NOT NULL DEFAULT '1.0' COMMENT '规则版本',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule (tenant_id, rule_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '财务规则表';

-- 2. tool_registry 加列（仅一次）
ALTER TABLE tool_registry
    ADD COLUMN scenario  VARCHAR(16) NOT NULL DEFAULT 'FINANCE' COMMENT '业务场景: FINANCE/GENERIC（P2b TaskPlanner 按此收敛工具目录）' AFTER version,
    ADD COLUMN cacheable TINYINT     NOT NULL DEFAULT 1        COMMENT '结果缓存: 1缓存 0不缓存（有状态工具置 0）' AFTER scenario;

-- 3. 种子（INSERT IGNORE 幂等：已存在跳过）
INSERT IGNORE INTO tool_registry (id, tenant_id, tool_code, tool_name, description, input_schema, enabled, version, scenario, cacheable) VALUES
    (2, 1, 'ocr_extract', '票据识别（OCR）',
     '识别报销附件票据，抽取金额/日期/商户/税号并按票据类型分类回写。入参 reimbId（报销单ID）+ attachmentIds（附件 file_record id 数组，取任务入参 attachments[].id）。',
     '{"type":"object","properties":{"reimbId":{"type":"integer"},"attachmentIds":{"type":"array","items":{"type":"integer"}}},"required":["reimbId","attachmentIds"]}',
     1, '1.0', 'FINANCE', 0),
    (3, 1, 'budget_query', '预算核算',
     '查部门当月剩余预算，返回预算占用与是否超支。入参 deptName（部门）+ claimDate（报销日期 YYYY-MM-DD，据此推导预算周期）+ amount（申报金额）。',
     '{"type":"object","properties":{"deptName":{"type":"string"},"claimDate":{"type":"string"},"amount":{"type":"number"}},"required":["deptName","claimDate","amount"]}',
     1, '1.0', 'FINANCE', 0),
    (4, 1, 'rule_check', '财务规则校验',
     '按财务规则（大额限额/报销时效等）校验报销单，返回命中规则与超标标记。入参 expenseType + claimDate + items + totalAmount。',
     '{"type":"object","properties":{"expenseType":{"type":"string"},"claimDate":{"type":"string"},"items":{"type":"array","items":{"type":"object"}},"totalAmount":{"type":"number"}},"required":["expenseType","claimDate","items","totalAmount"]}',
     1, '1.0', 'FINANCE', 0),
    (5, 1, 'duplicate_check', '重复报销检测',
     '按申请人+商户+金额+日期区间查历史报销单，返回疑似重复。入参 reimbId（报销单ID，agent-core 按此读当前+历史 OCR 商户）。',
     '{"type":"object","properties":{"reimbId":{"type":"integer"}},"required":["reimbId"]}',
     1, '1.0', 'FINANCE', 0);

INSERT IGNORE INTO budget (id, tenant_id, dept_name, period, total_budget, used_amount) VALUES
    (1, 1, '研发部', '2026-08', 100000.00, 32000.00),
    (2, 1, '财务部', '2026-08', 50000.00, 8000.00),
    (3, 1, '市场部', '2026-08', 80000.00, 45600.00),
    (4, 1, '销售部', '2026-08', 120000.00, 102400.00);

INSERT IGNORE INTO finance_rule (id, tenant_id, rule_code, rule_name, rule_type, rule_config, enabled, published, version) VALUES
    (1, 1, 'amount_limit', '大额报销限额', 'AMOUNT_LIMIT',
     '{"threshold":5000}', 1, 0, '1.0'),
    (2, 1, 'reimburse_expire', '报销时效', 'REIMBURSE_EXPIRE',
     '{"maxDays":30}', 1, 0, '1.0');
