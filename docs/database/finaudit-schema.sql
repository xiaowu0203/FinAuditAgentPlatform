-- =====================================================================
-- FinAuditAgentPlatform 数据库初始化脚本
-- 版本: P3b（多 Agent 角色化与规则流水线 + 审批工单闭环） ｜ 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 说明: 可直接整体执行；DROP TABLE IF EXISTS 保证幂等（会清空重灌）。
--       本机执行: mysql -uroot -p < docs/database/finaudit-schema.sql
--       已有数据的环境只跑增量: mysql -uroot -p < docs/database/migration-P3a.sql（再跑 migration-P3b.sql）
-- =====================================================================

CREATE DATABASE IF NOT EXISTS finaudit DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE finaudit;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS expense_attachment;
DROP TABLE IF EXISTS file_record;
DROP TABLE IF EXISTS expense_reimbursement;
DROP TABLE IF EXISTS budget;
DROP TABLE IF EXISTS finance_rule;
DROP TABLE IF EXISTS audit_record;
DROP TABLE IF EXISTS audit_ticket;
DROP TABLE IF EXISTS tool_execution_log;
DROP TABLE IF EXISTS tool_registry;
DROP TABLE IF EXISTS agent_task_step;
DROP TABLE IF EXISTS agent_task;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_permission;
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
--    状态机: PENDING -> RUNNING -> SUCCESS / FAILED / APPROVAL_PENDING / REJECTED
--    P3a 的 APPROVAL_PENDING 仅表示待人工复核；审批工单与审计记录属于 P3b
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
    agent_role  VARCHAR(32)   DEFAULT NULL COMMENT '执行角色（AgentRole: SCHEDULER/DOCUMENT_PARSER/BUDGET_CALCULATOR/RULE_VALIDATOR/RISK_AUDITOR；历史与 GENERIC 步骤为空）',
    input_params JSON         DEFAULT NULL COMMENT '步骤入参',
    output      JSON          DEFAULT NULL COMMENT '步骤输出',
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '步骤状态',
    error_msg   VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    retry_count INT           NOT NULL DEFAULT 0 COMMENT '重试次数',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0 未删 / 主键id 已删（配合 uk_task_step）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_step (task_id, step_no, deleted) COMMENT '任务内步骤唯一（含 deleted，重规划历史行不冲突）',
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
    scenario     VARCHAR(16)  NOT NULL DEFAULT 'FINANCE' COMMENT '业务场景: FINANCE/GENERIC（P2b TaskPlanner 按此收敛工具目录）',
    cacheable    TINYINT      NOT NULL DEFAULT 1 COMMENT '结果缓存: 1缓存 0不缓存（有状态工具置 0）',
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
--    status 对齐任务状态机: PENDING -> RUNNING -> SUCCESS / FAILED / APPROVAL_PENDING / REJECTED
--    P3a 进入 APPROVAL_PENDING 时，报销单展示 MANUAL_REVIEW；审批工单闭环属于 P3b
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
    items        JSON          NOT NULL COMMENT '报销明细 [{name,amount,amountType,quantity,unitPrice,date,city,hotelDays,hotelAmount,transportAmount,subsidyAmount}]（P2c 差旅/补贴评估字段，均 JSON 内嵌）',
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

-- ---------------------------------------------------------------------
-- 12. 部门预算表（P2b 预算核算工具 budget_query）
--    period 预算周期 YYYY-MM；P3b 审批通过后 used_amount 累加（待实现）
-- ---------------------------------------------------------------------
CREATE TABLE budget (
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

-- ---------------------------------------------------------------------
-- 13. 财务规则表（P2b 规则校验工具 rule_check；CRUD/发布归 P2c）
--    rule_config 仅存储结构化规则，不参与 WHERE（MySQL 5.7 JSON 检索限制）
-- ---------------------------------------------------------------------
CREATE TABLE finance_rule (
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
    deleted     BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0 未删 / 主键id 已删（配合 uk_rule_type，删除实现必须 SET deleted=id 禁用写 1）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule (tenant_id, rule_code),
    UNIQUE KEY uk_rule_type (tenant_id, rule_type, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '财务规则表（同租户同 rule_type 唯一，业务层 + 唯一索引双层兜底）';

-- ---------------------------------------------------------------------
-- 14. 审批工单表（P3b 人机协同审批闭环）
--    触发: 任务流水线判定 NEED_REVIEW（复核原因 review_reasons + 触发类型 trigger_type 确定性映射）
--    流转: PENDING -> APPROVED/REJECTED/TERMINATED/WITHDRAWN/AMENDED
--          AMENDED（提交人 resubmit 重跑中）: 重跑命中复位 PENDING / AUTO_PASS 转 APPROVED / 失败 onRerunFail 复位 PENDING
--          APPROVED -> WITHDRAW_PENDING -> WITHDRAWN（同意）/ APPROVED（拒绝）
--    rerun_count: 提交人 resubmit 重跑次数，上限 3（P3 §9 防死循环）；audit_level: 审批级数，P3 恒为 1（预留多级审批 TODO P5+）
-- ---------------------------------------------------------------------
CREATE TABLE audit_ticket (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    task_id        BIGINT        NOT NULL COMMENT '关联 agent_task.id',
    ticket_no      VARCHAR(64)   NOT NULL COMMENT '工单编号，如 AT-{taskNo}',
    title          VARCHAR(128)  NOT NULL COMMENT '任务标题（冗余展示）',
    trigger_type   VARCHAR(32)   NOT NULL COMMENT '触发类型: OVER_LIMIT 大额/超标 / RULE_FAIL 规则校验不通过 / RISK_HIT 风控命中（LLM_DECISION 兜底归此类）',
    risk_desc      VARCHAR(512)  DEFAULT NULL COMMENT '复核原因描述（review_reasons join 截断）',
    step_no        INT           DEFAULT NULL COMMENT '触发步骤（预留：决策跨步骤，暂置 NULL）',
    origin_amount  DECIMAL(12,2) DEFAULT NULL COMMENT '申报总额（任务入参 claimedTotal）',
    adjusted_amount DECIMAL(12,2) DEFAULT NULL COMMENT '重跑后修正总额（提交人 resubmit 时写，重跑后为最终金额）',
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '工单状态: PENDING/APPROVED/REJECTED/AMENDED(重跑中)/TERMINATED/WITHDRAW_PENDING(撤销待审)/WITHDRAWN(已撤回或已撤销)',
    audit_level    TINYINT       NOT NULL DEFAULT 1 COMMENT '审批级数（预留多级审批 TODO P5+，P3 恒 1）',
    rerun_count    INT           NOT NULL DEFAULT 0 COMMENT '提交人 resubmit 重跑次数（P3b 起财务不再 amend），上限 3',
    review_reasons JSON          DEFAULT NULL COMMENT '复核原因列表（JacksonTypeHandler 映射；重跑命中复位时刷新）',
    auditor_id     BIGINT        DEFAULT NULL COMMENT '最近处理人用户ID（提交人 resubmit/撤回/撤销申请动作不改此字段）',
    audit_comment  VARCHAR(512)  DEFAULT NULL COMMENT '最近处理意见',
    created_by     BIGINT        DEFAULT NULL COMMENT '申请人用户ID（任务提交人，工单动作权限归属）',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (tenant_id, ticket_no),
    UNIQUE KEY uk_task (tenant_id, task_id) COMMENT '一个 task 至多一个工单（同单续跑 1:1）',
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审批工单表';

-- ---------------------------------------------------------------------
-- 15. 审批留痕表（append-only：每次审批动作追加一条，审计溯源）
-- ---------------------------------------------------------------------
CREATE TABLE audit_record (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    ticket_id      BIGINT        NOT NULL COMMENT '工单ID',
    action         VARCHAR(20)   NOT NULL COMMENT '动作: SUBMIT 建单/APPROVE 通过/REJECT 驳回/AMEND 提交人修改重跑/TERMINATE 终止/RERUN 重跑复位/RERUN_FAILED 重跑失败复位/WITHDRAW 撤回/WITHDRAW_REQ 发起撤销/WITHDRAW_AGREE 同意撤销/WITHDRAW_REFUSE 拒绝撤销',
    before_amount  DECIMAL(12,2) DEFAULT NULL COMMENT '变更前金额',
    after_amount   DECIMAL(12,2) DEFAULT NULL COMMENT '变更后金额',
    before_data    JSON          DEFAULT NULL COMMENT '变更前快照（首条 SUBMIT 为 NULL；含 reimb 顶层字段+明细+附件，不含预签名URL/OSS路径）',
    after_data     JSON          DEFAULT NULL COMMENT '变更后快照（每次动作落一条，审批时点数据现场，可 before→after diff）',
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

-- ---------------------------------------------------------------------
-- 16. 权限目录表（P3.5a 轻量资源级 RBAC；平台级全局表，无 tenant_id）
--     ⚠️ 无 tenant_id：权限码由迁移脚本种子定义（代码即目录），运行期不增删；
--     查询必须走多租户拦截器 ignore 名单（common-mybatisplus-starter 已注册）
-- ---------------------------------------------------------------------
CREATE TABLE sys_permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    perm_code   VARCHAR(64)  NOT NULL COMMENT '权限标识符: 资源:操作（系统管理操作级）/ 资源级（业务）',
    perm_name   VARCHAR(64)  NOT NULL COMMENT '权限名称（分配界面展示）',
    perm_type   VARCHAR(8)   NOT NULL DEFAULT 'API' COMMENT '类型: MENU 菜单+接口 / API 仅接口',
    group_name  VARCHAR(32)  NOT NULL DEFAULT '业务' COMMENT '分组（分配界面分区展示）: 系统管理/财务业务/预留',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_code (perm_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限目录表（平台级全局，多租户拦截器须忽略）';

-- ---------------------------------------------------------------------
-- 17. 角色权限映射表（P3.5a：角色是权限的分配单位，替换式分配）
-- ---------------------------------------------------------------------
CREATE TABLE sys_role_permission (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT   NOT NULL DEFAULT 1 COMMENT '租户ID',
    role_id     BIGINT   NOT NULL COMMENT '角色ID（sys_role.id）',
    perm_id     BIGINT   NOT NULL COMMENT '权限ID（sys_permission.id）',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (tenant_id, role_id, perm_id),
    KEY idx_role (role_id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色权限映射表';

-- =====================================================================
-- Seed 数据（默认租户 + 管理员 + 角色 + 内置工具 + 预算 + 财务规则）
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

-- P3.5a 权限目录种子（固定主键；系统管理操作级 + 业务资源级，明细见 migration-P3.5a.sql）
INSERT INTO sys_permission (id, perm_code, perm_name, perm_type, group_name) VALUES
    (1,  'user:list',        '用户查询',     'MENU', '系统管理'),
    (2,  'user:create',      '用户新增',     'API',  '系统管理'),
    (3,  'user:update',      '用户编辑',     'API',  '系统管理'),
    (4,  'user:delete',      '用户删除',     'API',  '系统管理'),
    (5,  'user:assign-role', '用户角色绑定', 'API',  '系统管理'),
    (6,  'role:list',        '角色查询',     'MENU', '系统管理'),
    (7,  'role:create',      '角色新增',     'API',  '系统管理'),
    (8,  'role:update',      '角色编辑',     'API',  '系统管理'),
    (9,  'role:delete',      '角色删除',     'API',  '系统管理'),
    (10, 'role:assign-perm', '角色权限分配', 'API',  '系统管理'),
    (11, 'dept:manage',      '部门管理页',   'MENU', '系统管理'),
    (12, 'dept:create',      '部门新增',     'API',  '系统管理'),
    (13, 'dept:update',      '部门编辑',     'API',  '系统管理'),
    (14, 'dept:delete',      '部门删除',     'API',  '系统管理'),
    (15, 'tenant:manage',    '租户管理',     'API',  '系统管理'),
    (20, 'rule:manage',      '财务规则配置',     'MENU', '财务业务'),
    (21, 'reimb:viewAll',    '报销单全量可见',   'API',  '财务业务'),
    (22, 'task:viewAll',     '任务全量可见',     'API',  '财务业务'),
    (23, 'audit:viewAll',    '审批工单全量可见', 'API',  '财务业务'),
    (24, 'audit:approve',    '审批动作',         'API',  '财务业务'),
    (25, 'budget:viewAll',   '预算全部门查询',   'API',  '财务业务'),
    (30, 'dashboard:admin',  '管理员风控大盘',   'MENU', '预留');

-- P3.5a 内置角色默认权限（admin 全量；auditor 财务业务资源级；普通用户不授码）
INSERT INTO sys_role_permission (tenant_id, role_id, perm_id) VALUES
    (1, 1, 1), (1, 1, 2), (1, 1, 3), (1, 1, 4), (1, 1, 5),
    (1, 1, 6), (1, 1, 7), (1, 1, 8), (1, 1, 9), (1, 1, 10),
    (1, 1, 11), (1, 1, 12), (1, 1, 13), (1, 1, 14), (1, 1, 15),
    (1, 1, 20), (1, 1, 21), (1, 1, 22), (1, 1, 23), (1, 1, 24),
    (1, 1, 25), (1, 1, 30),
    (1, 2, 20), (1, 2, 21), (1, 2, 22), (1, 2, 23), (1, 2, 24);

-- 内置金额核验工具（P1 首个落地工具，金额一律 Decimal）
INSERT INTO tool_registry (id, tenant_id, tool_code, tool_name, description, input_schema, enabled, version) VALUES
    (1, 1, 'amount_verify', '金额核验工具',
     '加总明细金额并与申报总额比对，返回是否一致及差额。入参 items:[{name,amount}] + claimedTotal；items 元素可含 amountType/quantity/unitPrice/date 等辅助字段（仅核验 amount）。',
     '{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"amountType":{"type":["string","null"]},"quantity":{"type":["number","null"]},"unitPrice":{"type":["number","null"]},"date":{"type":["string","null"]}},"required":["name","amount"]}},"claimedTotal":{"type":"number"}},"required":["items","claimedTotal"]}',
     1, '1.0');

-- P2b 四个审核工具（scenario=FINANCE 供 TaskPlanner 收敛；cacheable=0 有状态查询不缓存）
INSERT INTO tool_registry (id, tenant_id, tool_code, tool_name, description, input_schema, enabled, version, scenario, cacheable) VALUES
    (2, 1, 'ocr_extract', '票据识别（OCR）',
     '识别报销附件票据，抽取金额/日期/商户/税号并按票据类型分类回写。入参 reimbId（报销单ID）+ attachmentIds（附件 file_record id 数组，取任务入参 attachments[].id）。',
     '{"type":"object","properties":{"reimbId":{"type":"integer"},"attachmentIds":{"type":"array","items":{"type":"integer"}}},"required":["reimbId","attachmentIds"]}',
     1, '1.0', 'FINANCE', 0),
    (3, 1, 'budget_query', '预算核算',
     '查部门当月剩余预算，返回预算占用与是否超支。入参 deptName（部门）+ claimDate（报销日期 YYYY-MM-DD，据此推导预算周期）+ amount（申报金额）。',
     '{"type":"object","properties":{"deptName":{"type":"string"},"claimDate":{"type":"string"},"amount":{"type":"number"}},"required":["deptName","claimDate","amount"]}',
     1, '1.0', 'FINANCE', 0),
    (4, 1, 'rule_check', '财务规则校验',
     '按财务规则（大额限额/报销时效/差旅标准/补贴限额）校验报销单，返回命中规则与超标标记。入参 expenseType + claimDate + items + totalAmount。',
     '{"type":"object","properties":{"expenseType":{"type":"string"},"claimDate":{"type":"string"},"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"amountType":{"type":["string","null"]},"quantity":{"type":["number","null"]},"unitPrice":{"type":["number","null"]},"date":{"type":["string","null"]},"city":{"type":["string","null"]},"hotelDays":{"type":["integer","null"]},"hotelAmount":{"type":["number","null"]},"transportAmount":{"type":["number","null"]},"subsidyAmount":{"type":["number","null"]}},"required":["name","amount"]}},"totalAmount":{"type":"number"}},"required":["expenseType","claimDate","items","totalAmount"]}',
     1, '1.0', 'FINANCE', 0),
    (5, 1, 'duplicate_check', '重复报销检测',
     '按申请人+商户+金额+日期区间查历史报销单，返回疑似重复。入参 reimbId（报销单ID，agent-core 按此读当前+历史 OCR 商户）。',
     '{"type":"object","properties":{"reimbId":{"type":"integer"}},"required":["reimbId"]}',
     1, '1.0', 'FINANCE', 0);

-- P2b 部门预算种子（默认租户 2026-08，部分已用）
INSERT INTO budget (id, tenant_id, dept_name, period, total_budget, used_amount) VALUES
    (1, 1, '研发部', '2026-08', 100000.00, 32000.00),
    (2, 1, '财务部', '2026-08', 50000.00, 8000.00),
    (3, 1, '市场部', '2026-08', 80000.00, 45600.00),
    (4, 1, '销售部', '2026-08', 120000.00, 102400.00);

-- P2c 财务规则种子（四类全结构化；published=1 即 Nacos 生效集，改后置 0 需重新发布）
INSERT INTO finance_rule (id, tenant_id, rule_code, rule_name, rule_type, rule_config, enabled, published, version) VALUES
    (1, 1, 'amount_limit', '大额报销限额', 'AMOUNT_LIMIT',
     '{"threshold":5000}', 1, 1, '1.0'),
    (2, 1, 'reimburse_expire', '报销时效', 'REIMBURSE_EXPIRE',
     '{"maxDays":30}', 1, 1, '1.0'),
    (3, 1, 'travel_standard', '差旅标准', 'TRAVEL_STANDARD',
     '{"standards":[{"city":"北京","hotelDaily":500,"transportTotal":3000},{"city":"上海","hotelDaily":450,"transportTotal":2500}]}',
     1, 1, '1.0'),
    (4, 1, 'subsidy_limit', '补贴限额', 'SUBSIDY_LIMIT',
     '{"dailyAmount":200}', 1, 1, '1.0');
