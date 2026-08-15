-- =====================================================================
-- FinAuditAgentPlatform 数据库初始化脚本
-- 版本: P2a-重构（拆 file-service + 报销域迁 agent-core） ｜ 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 说明: 可直接整体执行；DROP TABLE IF EXISTS 保证幂等（会清空重灌）。
--       本机执行: mysql -uroot -p < docs/database/finaudit-schema.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS finaudit DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE finaudit;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS expense_attachment;
DROP TABLE IF EXISTS file_record;
DROP TABLE IF EXISTS expense_reimbursement;
DROP TABLE IF EXISTS tool_execution_log;
DROP TABLE IF EXISTS tool_registry;
DROP TABLE IF EXISTS agent_task_step;
DROP TABLE IF EXISTS agent_task;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_tenant;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 1. 租户表
-- ---------------------------------------------------------------------
CREATE TABLE sys_tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_code VARCHAR(32)  NOT NULL COMMENT '租户编码',
    tenant_name VARCHAR(64)  NOT NULL COMMENT '租户名称',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '租户表';

-- ---------------------------------------------------------------------
-- 2. 用户表
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id  BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID',
    username   VARCHAR(64)  NOT NULL COMMENT '登录名',
    password   VARCHAR(128) NOT NULL COMMENT 'BCrypt 哈希',
    real_name  VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
    phone      VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (tenant_id, username),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ---------------------------------------------------------------------
-- 3. 角色表
-- ---------------------------------------------------------------------
CREATE TABLE sys_role (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id  BIGINT      NOT NULL DEFAULT 1 COMMENT '租户ID',
    role_code  VARCHAR(32) NOT NULL COMMENT '角色编码',
    role_name  VARCHAR(64) NOT NULL COMMENT '角色名称',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role (tenant_id, role_code),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色表';

-- ---------------------------------------------------------------------
-- 4. 用户-角色关联表
-- ---------------------------------------------------------------------
CREATE TABLE sys_user_role (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id  BIGINT   NOT NULL DEFAULT 1 COMMENT '租户ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    role_id    BIGINT   NOT NULL COMMENT '角色ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted    TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (tenant_id, user_id, role_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户角色关联表';

-- ---------------------------------------------------------------------
-- 5. Agent 任务表（任务持久化 + 状态机载体）
--    状态机: PENDING -> RUNNING -> SUCCESS / FAILED（预留 MANUAL_REVIEW）
-- ---------------------------------------------------------------------
CREATE TABLE agent_task (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    task_no       VARCHAR(40)   NOT NULL COMMENT '任务编号，如 T20260813120000123456',
    title         VARCHAR(128)  NOT NULL COMMENT '任务标题',
    task_type     VARCHAR(20)   NOT NULL DEFAULT 'GENERIC' COMMENT '业务类型：REIMBURSEMENT 报销审核 / GENERIC 通用分析（P2a 新增，规划器按业务注入提示词/工具）',
    input_params  JSON          NOT NULL COMMENT '任务入参（原始输入，含明细金额等）',
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    total_steps   INT           NOT NULL DEFAULT 0 COMMENT '总步骤数',
    finished_steps INT          NOT NULL DEFAULT 0 COMMENT '已完成步骤数',
    result        JSON          DEFAULT NULL COMMENT '最终结果（汇总 JSON）',
    error_msg     VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    created_by    BIGINT        DEFAULT NULL COMMENT '提交人用户ID',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_no (task_no),
    KEY idx_tenant_status (tenant_id, status),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 任务表';

-- ---------------------------------------------------------------------
-- 6. Agent 任务步骤表（断点续跑载体）
--    step_type: LLM / TOOL
-- ---------------------------------------------------------------------
CREATE TABLE agent_task_step (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    task_id     BIGINT        NOT NULL COMMENT '任务ID',
    step_no     INT           NOT NULL COMMENT '步骤序号(从1开始)',
    step_name   VARCHAR(64)   NOT NULL COMMENT '步骤名称',
    step_type   VARCHAR(16)   NOT NULL COMMENT '步骤类型: LLM/TOOL',
    tool_name   VARCHAR(64)   DEFAULT NULL COMMENT 'TOOL 步骤的工具编码',
    input_params JSON         DEFAULT NULL COMMENT '步骤入参',
    output      JSON          DEFAULT NULL COMMENT '步骤输出',
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '步骤状态',
    error_msg   VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    retry_count INT           NOT NULL DEFAULT 0 COMMENT '重试次数',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_step (task_id, step_no),
    KEY idx_task (task_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 任务步骤表';

-- ---------------------------------------------------------------------
-- 7. 工具注册表（工具治理：注册/入参Schema/启停）
-- ---------------------------------------------------------------------
CREATE TABLE tool_registry (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID',
    tool_code    VARCHAR(64)  NOT NULL COMMENT '工具编码',
    tool_name    VARCHAR(64)  NOT NULL COMMENT '工具名称',
    description  VARCHAR(256) DEFAULT NULL COMMENT '工具描述',
    input_schema JSON         NOT NULL COMMENT '入参 JSON Schema（强校验）',
    enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用 0禁用',
    version      VARCHAR(16)  NOT NULL DEFAULT '1.0' COMMENT '工具版本',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool (tenant_id, tool_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工具注册表';

-- ---------------------------------------------------------------------
-- 8. 工具执行日志表
-- ---------------------------------------------------------------------
CREATE TABLE tool_execution_log (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    task_id       BIGINT        DEFAULT NULL COMMENT '关联任务ID',
    step_id       BIGINT        DEFAULT NULL COMMENT '关联步骤ID',
    tool_code     VARCHAR(64)   NOT NULL COMMENT '工具编码',
    input_params  JSON          NOT NULL COMMENT '入参',
    result        JSON          DEFAULT NULL COMMENT '执行结果',
    cost_time_ms  BIGINT        DEFAULT NULL COMMENT '耗时(毫秒)',
    status        VARCHAR(20)   NOT NULL COMMENT '执行状态: SUCCESS/FAILED',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task (task_id),
    KEY idx_tool (tool_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工具执行日志表';

-- ---------------------------------------------------------------------
-- 9. 报销单表（P2a 单据闭环）
--    status 对齐任务状态机: PENDING -> RUNNING -> SUCCESS / FAILED（预留 MANUAL_REVIEW）
--    items 存提交明细，仅作存储不参与 WHERE 过滤（MySQL 5.7 JSON 检索限制）
-- ---------------------------------------------------------------------
CREATE TABLE expense_reimbursement (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    reimb_no     VARCHAR(40)   NOT NULL COMMENT '报销单号，如 R2026081512345678',
    title        VARCHAR(128)  NOT NULL COMMENT '报销标题',
    expense_type VARCHAR(32)   NOT NULL COMMENT '费用类型: TRAVEL/ENTERTAINMENT/OFFICE',
    applicant_id BIGINT        NOT NULL COMMENT '申请人用户ID',
    dept_name    VARCHAR(64)   NOT NULL COMMENT '部门',
    total_amount DECIMAL(12,2) NOT NULL COMMENT '申报总金额（Decimal 强制）',
    task_id      BIGINT        DEFAULT NULL COMMENT '关联 agent_task.id（提交后反写）',
    status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '审核状态（对齐任务状态机）',
    claim_date   DATE          NOT NULL COMMENT '报销日期',
    remark       VARCHAR(512)  DEFAULT NULL COMMENT '备注',
    items        JSON          NOT NULL COMMENT '报销明细 [{name,amount,amountType,quantity,unitPrice,date}]',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reimb_no (reimb_no),
    KEY idx_tenant_status (tenant_id, status),
    KEY idx_applicant (tenant_id, applicant_id),
    KEY idx_task (task_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '报销单表';

-- ---------------------------------------------------------------------
-- 10. 报销业务附件表（P2a-重构）
--    仅存 file_record 引用 + 业务字段（fileType/ocrStatus/ocrResult）；
--    文件元数据（fileName/objectName）在 file-service 的 file_record，经 FileServiceFeign 联取
-- ---------------------------------------------------------------------
CREATE TABLE expense_attachment (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    reimb_id      BIGINT        DEFAULT NULL COMMENT '报销单ID（提交绑定时回填）',
    file_record_id BIGINT       NOT NULL COMMENT 'file_record.id（文件元数据在 file-service）',
    file_type     VARCHAR(32)   NOT NULL DEFAULT 'OTHER' COMMENT '附件类型: INVOICE/ITINERARY/CONTRACT/OTHER（P2a 默认 OTHER，分类归 P2b OCR）',
    ocr_status    VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'OCR状态: PENDING/SUCCESS/FAILED',
    ocr_result    JSON          DEFAULT NULL COMMENT 'OCR抽取结果',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_reimb (reimb_id),
    KEY idx_file_record (file_record_id),
    KEY idx_tenant (tenant_id),
    KEY idx_ocr_status (ocr_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '报销业务附件表';

-- ---------------------------------------------------------------------
-- 11. 文件元数据表（file-service：纯二进制资源，无财务业务字段）
--    object_name 为对象存储 key（含租户前缀，防跨租户碰撞）；业务附件经 file_record_id 引用本表
-- ---------------------------------------------------------------------
CREATE TABLE file_record (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID',
    file_name    VARCHAR(255) NOT NULL COMMENT '原始文件名',
    object_name  VARCHAR(255) NOT NULL COMMENT '对象存储 key（含租户前缀 {tenantId}/{yyyyMM}/{uuid}{ext}）',
    content_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream' COMMENT 'MIME 类型',
    size         BIGINT       NOT NULL DEFAULT 0 COMMENT '字节大小',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件元数据表（file-service）';

-- =====================================================================
-- Seed 数据（默认租户 + 管理员 + 角色 + 内置金额核验工具）
-- =====================================================================

INSERT INTO sys_tenant (id, tenant_code, tenant_name, status) VALUES
    (1, 'default', '默认租户', 1);

INSERT INTO sys_role (id, tenant_id, role_code, role_name) VALUES
    (1, 1, 'admin',   '管理员'),
    (2, 1, 'auditor', '审核员');

-- admin 密码: admin123（BCrypt 哈希，P1.4 起为真实哈希；明文仅存在于本注释与 .env.example 约定，生产必改）
INSERT INTO sys_user (id, tenant_id, username, password, real_name, status) VALUES
    (1, 1, 'admin', '$2a$10$Cl.mMuDniwH4biiUNXY1lOKre0Ucg91fbnPfGg8R8nHvKBNaUc4Lq', '系统管理员', 1);

INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES
    (1, 1, 1, 1);

-- 内置金额核验工具（P1 首个落地工具，金额一律 Decimal）
INSERT INTO tool_registry (id, tenant_id, tool_code, tool_name, description, input_schema, enabled, version) VALUES
    (1, 1, 'amount_verify', '金额核验工具',
     '加总明细金额并与申报总额比对，返回是否一致及差额。入参 items:[{name,amount}] + claimedTotal；items 元素可含 amountType/quantity/unitPrice/date 等辅助字段（仅核验 amount）。',
     '{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"amountType":{"type":"string"},"quantity":{"type":"number"},"unitPrice":{"type":"number"},"date":{"type":"string"}},"required":["name","amount"]}},"claimedTotal":{"type":"number"}},"required":["items","claimedTotal"]}',
     1, '1.0');
