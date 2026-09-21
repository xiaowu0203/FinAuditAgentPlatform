-- =====================================================================
-- P3.8 增量迁移（累计脚本：R1 / R2 / R3 / R4 全部 DDL 与种子）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3.8.sql
--
-- 本脚本已累计 R1~R4 的增量，**每节都幂等**，可整份重复执行（已实测重复执行无副作用）。
-- 节次一览：
--   1  新增 budget_occupancy 占用记账表（R1）
--   2  核对现有预算行（R1）
--   3  新增 invoice_record 发票标识符投影表（R2）
--   4  历史数据回填：从 ocr_result JSON 抽票号投影（R2）
--   5  回填报告：命中数 + 明细（R2）
--   6  新增 invoice_reimb_link 发票—报销单关联表 + 历史归属回填（R3 架构修复）
--   7  注册 invoice_match 工具（R3）
--   8  核对工具注册表（R3）
--   9  audit_ticket 增 review_findings 结构化问题项列（R4）
--   10 核对工单表新列（R4）
--   11 agent_task 增 correction_count / self_check_result（R5 语义自校验）
--   12 核对 agent_task 自校验两列（R5）
--
-- 背景（业务走查 B-1/B-2）：budget.used_amount 此前全仓无写入点，只做只读预检，
--   同一部门同月多笔报销全部报「预算充足」，系统一次都不拦。
-- 背景（B-3）：发票代码/号码在 OCR 链路里已解析却被丢弃，下游查重只能靠金额+日期启发式。
-- 背景（B-4/B-5）：查重只看「同额同商户」，票据与明细从未对上；
--   R3 用 invoice_record 做按票号硬命中，并新增 invoice_match 做票据-明细交叉核验。
-- 背景（B-7）：驳回只给一句 risk_desc 文字，提交人不知道该改哪一行、改成多少；
--   R4 把复核原因升级为结构化问题项（定位明细行 + 期望/实际/差额/建议）。
--
-- ⚠️ 执行顺序：①③ 必须先于 ④（回填依赖表已建出）；⑥ 必须先于 ⑦ 之后的核对
-- =====================================================================

USE finaudit;

-- ---------------------------------------------------------------------
-- 1. 预算占用记账表（R1）
--    一个报销单至多一条记录（uk_reimb），因其生命周期内占用/释放成对发生；
--    occupy_count / release_count 记录发生次数（resubmit 重跑会再次占用），
--    配平公式：SUM(amount WHERE status='OCCUPIED') == budget.used_amount
--    注意只算当前占用态：行转 RELEASED 时 used_amount 已扣回，RELEASED 行整条跳过
--    （曾误写成 SUM(占用)-SUM(释放)，等于把释放额扣减两次，已修正）
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

-- ---------------------------------------------------------------------
-- 3. 发票标识符投影表（R2）
--    expense_attachment.ocr_result 是 JSON 且 MySQL 5.7 无法对 JSON 内部字段建索引，
--    票号只能全表扫描 + 应用层解析，无法担当查重主键，故投影出本表。
--    唯一键含 deleted：支持逻辑删除后重新插入（同 agent_task_step.uk_task_step 惯例）。
--    发票代码/号码缺失统一归一为 ''（空串而非 NULL），使唯一索引稳定生效。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS invoice_record (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID',
    invoice_code  VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '发票代码（缺失归一为空串）',
    invoice_num   VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '发票号码（缺失归一为空串）',
    seller_tax_no VARCHAR(64)   DEFAULT NULL COMMENT '销售方税号（SellerRegisterNum）',
    reimb_id      BIGINT        DEFAULT NULL COMMENT '归属报销单ID',
    file_record_id BIGINT       NOT NULL COMMENT '来源附件 file_record.id',
    attachment_id BIGINT        DEFAULT NULL COMMENT '来源 expense_attachment.id',
    amount        DECIMAL(12,2) DEFAULT NULL COMMENT '票面金额（价税合计）',
    inv_date      DATE          DEFAULT NULL COMMENT '开票日期（OCR 中文格式日期解析成功时落值）',
    seen_count    INT           NOT NULL DEFAULT 1 COMMENT '同一张票被识别的次数（重复入账时 >1）',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice (tenant_id, invoice_code, invoice_num, deleted) COMMENT '同一张票一租户一条（含 deleted，支持逻辑删除后重插）',
    KEY idx_invoice (tenant_id, invoice_code, invoice_num) COMMENT 'R3 按票硬命中查重',
    KEY idx_reimb (reimb_id),
    KEY idx_file_record (file_record_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '发票标识符投影表（P3.8 R2；绕开 MySQL 5.7 JSON 检索限制）';

-- ---------------------------------------------------------------------
-- 4. 历史数据回填（R2-4）
--    从既有 expense_attachment.ocr_result JSON 抽发票代码/号码投影到 invoice_record。
--    只回填「票号非空」的行——票号缺失的票据无法参与按票查重，投影进去只会与唯一键相互冲突；
--    且迁移脚本最外层的 WHERE 直接要求 invoiceCode 与 invoiceNum 均非空，
--    JSON 路径不存在时 JSON_EXTRACT 返回 NULL，自然被过滤，无需额外判空。
--
--    ⚠️ 开票日期取 $.ocrDate 而非 $.date：
--      $.date 是**展示用原样串**（财会版 classifierId=10001 实际返回中文「2025年04月02日」），
--      $.ocrDate 才是 R2 起新增的可解析 ISO 串（yyyy-MM-dd）。
--      而本回填处理的正是 R2 之前入库的存量数据，那批数据里 **没有 ocrDate 字段、只有中文 date**，
--      所以必须两种格式都试：先 ISO，再中文——只试 ISO 会让存量单据的开票日期全部回填为 NULL（实测踩过）。
--    INSERT IGNORE 保证可重复执行；seen_count 记录同一张票命中几行附件。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO invoice_record
    (tenant_id, invoice_code, invoice_num, seller_tax_no, reimb_id, file_record_id,
     attachment_id, amount, inv_date, seen_count)
SELECT a.tenant_id,
       COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.invoiceCode'))), ''),
       COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.invoiceNum'))), ''),
       NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.taxNo'))), ''),
       a.reimb_id,
       a.file_record_id,
       a.id,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.amount')) AS DECIMAL(12,2)),
       COALESCE(
           STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.ocrDate')), '%Y-%m-%d'),
           STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.date')), '%Y-%m-%d'),
           STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.date')), '%Y年%m月%d日'),
           STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.date')), '%Y年%c月%e日')
       ),
       1
FROM expense_attachment a
WHERE a.deleted = 0
  AND a.ocr_status = 'SUCCESS'
  AND a.ocr_result IS NOT NULL
  AND COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.invoiceNum'))), '') <> ''
  AND COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.invoiceCode'))), '') <> ''
  -- 依赖 (tenant_id, invoice_code, invoice_num) 前缀做同票去重：同一张票多行附件只投影一条
  AND a.id = (SELECT MIN(a2.id) FROM expense_attachment a2
               WHERE a2.deleted = 0
                 AND a2.tenant_id = a.tenant_id
                 AND COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a2.ocr_result, '$.invoiceCode'))), '') =
                     COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.invoiceCode'))), '')
                 AND COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a2.ocr_result, '$.invoiceNum'))), '') =
                     COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.ocr_result, '$.invoiceNum'))), ''));

-- ---------------------------------------------------------------------
-- 5. 回填报告：命中数 + 明细（R2-4 验收断言「回填报告输出命中数」）
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS backfilled_invoice_rows
FROM invoice_record
WHERE deleted = 0;

SELECT r.id, r.tenant_id, r.invoice_code, r.invoice_num, r.seller_tax_no,
       r.reimb_id, r.file_record_id, r.amount, r.inv_date
FROM invoice_record r
WHERE r.deleted = 0
ORDER BY r.id;

-- ---------------------------------------------------------------------
-- 6. 发票—报销单关联表（R3 修复）
--    ⚠️ 为什么必须补这张表：invoice_record 每张票只有一行、只带一个 reimb_id，
--    「同一张票被多张报销单共用」这个事实【存不下】—— 而按票查重的判定恰恰要回答
--    「这张票还属于哪张单」。R3 联调实测：同一张票提交 5 次后，投影行 reimb_id
--    只记录了最后一次（=57），于是 53/54/55/56 各单查询时都把自己排除掉，
--    硬命中【恒不触发】（脚本判据① 报 dupLevel=NONE）。
--    故把归属关系拆到本表（一对多：一票多单）；invoice_record.reimb_id 降级为
--    「最近一次归属」仅供展示。
--    幂等：CREATE TABLE IF NOT EXISTS；可重复执行
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS invoice_reimb_link (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id         BIGINT      NOT NULL DEFAULT 1 COMMENT '租户ID',
    invoice_record_id BIGINT      NOT NULL COMMENT '发票投影ID（invoice_record.id）',
    reimb_id          BIGINT      NOT NULL COMMENT '报销单ID',
    file_record_id    BIGINT      DEFAULT NULL COMMENT '来源附件 file_record.id（最近一次）',
    seen_count        INT         NOT NULL DEFAULT 1 COMMENT '同一张票在本单内被识别的次数',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_reimb (tenant_id, invoice_record_id, reimb_id, deleted) COMMENT '一票一单一条（含 deleted，支持逻辑删除后重插）',
    KEY idx_invoice (invoice_record_id),
    KEY idx_reimb (reimb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '发票—报销单关联表（P3.8 R3；承接一票多单的归属关系）';

-- 历史归属回填：已存在的投影行按其当前 reimb_id 补一条关联（它们本就只记录了一个归属）
INSERT IGNORE INTO invoice_reimb_link (tenant_id, invoice_record_id, reimb_id, file_record_id, seen_count)
SELECT r.tenant_id, r.id, r.reimb_id, r.file_record_id, r.seen_count
FROM invoice_record r
WHERE r.deleted = 0 AND r.reimb_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 7. 工具注册：invoice_match（R3-3 / R3-5）
--    tool-service 执行工具前会按 tool_code 查 tool_registry，查不到即抛
--    「工具未注册或已禁用」，故新工具必须在此登记，否则流水线到该步直接失败。
--    幂等：WHERE NOT EXISTS，可重复执行。
-- ---------------------------------------------------------------------
INSERT INTO tool_registry
    (tenant_id, tool_code, tool_name, description, input_schema, enabled, version, scenario, cacheable)
SELECT 1, 'invoice_match', '票据-明细交叉核验',
       '把票面金额与申报明细交叉比对（票面合计 vs 申报合计、单笔是否超过票面合计），并对发票代码位数/开票日期做离线验真。补 amount_verify（只校验明细与总额自洽）与 rule_check（只校验限额标准）都覆盖不到的「明细与票面对不上」缺口。入参 reimbId + items + claimedTotal。',
       '{"type":"object","properties":{"reimbId":{"type":"integer"},"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"}},"required":["name","amount"]}},"claimedTotal":{"type":"number"}},"required":["reimbId","items"]}',
       1, '1.0', 'FINANCE', 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tool_registry WHERE tenant_id = 1 AND tool_code = 'invoice_match'
);

-- ---------------------------------------------------------------------
-- 8. 核对：工具注册表最终状态（应含 invoice_match）
-- ---------------------------------------------------------------------
SELECT id, tool_code, tool_name, enabled, scenario, cacheable
FROM tool_registry
WHERE deleted = 0
ORDER BY id;

-- ---------------------------------------------------------------------
-- 9. 审批工单增结构化问题项列（R4-3）
--    背景（业务走查 B-7）：现状驳回只给一句 risk_desc 文字，提交人不知道该改哪一行、改成多少。
--    新增 review_findings 承载 ReviewFinding 列表（定位明细行 + 期望/实际/差额/建议），
--    前端据此在编辑页标红对应行并预填建议值，直接支撑「驳回重提引导」。
--    review_reasons 继续保留（字符串摘要，兼容既有消费方）。
--    幂等：MySQL 5.7 无 ADD COLUMN IF NOT EXISTS，用 information_schema 判定后动态执行。
-- ---------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'audit_ticket'
                      AND column_name = 'review_findings');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE audit_ticket ADD COLUMN review_findings JSON DEFAULT NULL COMMENT ''结构化审核问题项（P3.8 R4-3：定位明细行 + 期望/实际/差额/建议，支撑驳回重提引导）'' AFTER review_reasons',
    'SELECT ''review_findings 已存在，跳过'' AS skip_msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 10. 核对：工单表新列已就位
-- ---------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'audit_ticket' AND COLUMN_NAME = 'review_findings';

-- ---------------------------------------------------------------------
-- 11. agent_task 增自校验两列（R5-4）
--     correction_count：语义自校验命中矛盾后重跑风控语义步骤的次数（上限 1，超限转人工）
--     self_check_result：自校验结果快照（是否通过 + 矛盾/幻觉清单 + 断言条数）
--     幂等：information_schema 判定后动态 DDL（MySQL 5.7 无 ADD COLUMN IF NOT EXISTS）
-- ---------------------------------------------------------------------
SET @col_cc = (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'agent_task' AND column_name = 'correction_count');
SET @ddl_cc = IF(@col_cc = 0,
    'ALTER TABLE agent_task ADD COLUMN correction_count INT NOT NULL DEFAULT 0 COMMENT ''自校验纠错次数（P3.8 R5：命中矛盾重跑风控语义步骤时累加）'' AFTER error_msg',
    'SELECT ''correction_count 已存在，跳过'' AS skip_msg');
PREPARE stmt_cc FROM @ddl_cc;
EXECUTE stmt_cc;
DEALLOCATE PREPARE stmt_cc;

SET @col_scr = (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'agent_task' AND column_name = 'self_check_result');
SET @ddl_scr = IF(@col_scr = 0,
    'ALTER TABLE agent_task ADD COLUMN self_check_result JSON DEFAULT NULL COMMENT ''语义自校验结果（P3.8 R5：是否通过 + 矛盾/幻觉清单 + 断言条数）'' AFTER correction_count',
    'SELECT ''self_check_result 已存在，跳过'' AS skip_msg');
PREPARE stmt_scr FROM @ddl_scr;
EXECUTE stmt_scr;
DEALLOCATE PREPARE stmt_scr;

-- ---------------------------------------------------------------------
-- 12. 核对：agent_task 自校验两列已就位
-- ---------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'agent_task'
  AND COLUMN_NAME IN ('correction_count', 'self_check_result')
ORDER BY ORDINAL_POSITION;

-- ---------------------------------------------------------------------
-- 13. tool_registry 增出参 Schema 列（R6-4）
--     目的：让「工具给错数据」在工具边界就暴露，而不是一路流到 LLM 上下文与结构化问题项里
--     （R4-7 手工装配丢字段就是这类故障）。列为空表示不校验，兼容存量工具。
--     ⚠️ 执行顺序：本步必须在【重启 tool-service】之前完成，
--        否则实体新增字段会让所有 tool_registry 查询报 Unknown column。
--     幂等：information_schema 判定后动态 DDL（MySQL 5.7 无 ADD COLUMN IF NOT EXISTS）
-- ---------------------------------------------------------------------
SET @col_os = (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'tool_registry'
                  AND column_name = 'output_schema');
SET @ddl_os = IF(@col_os = 0,
    'ALTER TABLE tool_registry ADD COLUMN output_schema JSON DEFAULT NULL COMMENT ''出参 JSON Schema（P3.8 R6-4；非空则执行后校验出参形状）'' AFTER input_schema',
    'SELECT ''output_schema 已存在，跳过'' AS skip_msg');
PREPARE stmt_os FROM @ddl_os;
EXECUTE stmt_os;
DEALLOCATE PREPARE stmt_os;

-- ---------------------------------------------------------------------
-- 14. 工具契约对称化：budget_query 入参 Schema + amount_verify 出参 Schema（R6-4）
--     ① budget_query 原先 required 写死 deptName，而工具与防越权守卫都支持 deptId 定位部门，
--        导致「只传 deptId」的调用被 Schema 直接拦掉（契约与实现不对称）。
--        改为 required 只保留 claimDate/amount，deptName 与 deptId 用 anyOf 二选一。
--     ② amount_verify 出参形状由执行器保证（total/claimedTotal/match/diff/message），
--        补 output_schema 作为可校验样板；未在 properties 里声明的额外字段不禁止
--        （JSON Schema 默认 additionalProperties=true），避免过度约束后续演进。
--     幂等：纯 UPDATE，可重复执行。
-- ---------------------------------------------------------------------
UPDATE tool_registry
SET input_schema = '{"type":"object","properties":{"deptName":{"type":"string"},"deptId":{"type":"integer"},"reimbId":{"type":"integer"},"claimDate":{"type":"string"},"amount":{"type":"number"}},"required":["claimDate","amount"],"anyOf":[{"required":["deptName"]},{"required":["deptId"]}]}',
    description  = '查部门当月剩余预算，返回预算占用与是否超支。入参 deptName 或 deptId（二者任一即可定位部门）+ claimDate（报销日期 YYYY-MM-DD，据此推导预算周期）+ amount（申报金额）。'
WHERE deleted = 0 AND tool_code = 'budget_query';

UPDATE tool_registry
SET output_schema = '{"type":"object","properties":{"total":{"type":"number"},"claimedTotal":{"type":"number"},"match":{"type":"boolean"},"diff":{"type":"number"},"message":{"type":"string"}},"required":["total","claimedTotal","match"]}'
WHERE deleted = 0 AND tool_code = 'amount_verify';

-- ---------------------------------------------------------------------
-- 16. 模型调用台账表（R9-1）
--     目的：Token 用量此前只在内存累加（usageSnapshot 无消费方），进程重启即归零 = 成本指标不存在。
--     台账为「一次模型调用一行」的事实表：带 租户/任务/步骤/场景，构成本与效率指标的数据源。
--     ⚠️ 为什么不做成 agent_task_step 的扩展列：一次步骤可能多次调用模型
--        （结构化输出解析失败会重试、故障会切备用模型），加列只能存最后一条，成本与失败率都算不准。
--     幂等：CREATE TABLE IF NOT EXISTS
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS model_call_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id         BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID',
    model_type        VARCHAR(32)  NOT NULL COMMENT '模型类型（DEEPSEEK 等）',
    model_name        VARCHAR(64)  DEFAULT NULL COMMENT '模型名（如 deepseek-chat）',
    scene             VARCHAR(32)  DEFAULT NULL COMMENT '调用场景（llm_step/task_plan 等）',
    task_id           BIGINT       DEFAULT NULL COMMENT '任务ID',
    step_id           BIGINT       DEFAULT NULL COMMENT '步骤ID',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '输入 tokens',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 tokens',
    total_tokens      INT          NOT NULL DEFAULT 0 COMMENT '总 tokens（冗余列，便于直接聚合）',
    latency_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '调用耗时（毫秒，含故障切换）',
    success           TINYINT      NOT NULL DEFAULT 1 COMMENT '是否成功: 1成功 0失败',
    fallback_used     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否走备用模型: 1是 0否',
    error_msg         VARCHAR(500) DEFAULT NULL COMMENT '失败原因（截断保存）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, created_at),
    KEY idx_task (task_id),
    KEY idx_step (step_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模型调用台账（P3.8 R9-1：成本与效率指标数据源）';

-- 回填说明：本表为新增观测表，历史调用无数据源可回填（内存计数已随进程重启丢失），
-- 故只从上线时刻开始记录；指标文档中需注明「成本指标的起始时间」。

-- ---------------------------------------------------------------------
-- 17. 任务/步骤耗时列（R9-2）
--     agent_task.duration_ms：本次执行（started_at → 终态）耗时
--     agent_task_step.duration_ms：LLM 步骤 = 模型调用耗时；TOOL 步骤 = 分发 → tool.result 回调
--     幂等：information_schema 判定后动态 DDL（MySQL 5.7 无 ADD COLUMN IF NOT EXISTS）
-- ---------------------------------------------------------------------
SET @col_task_dur = (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema = DATABASE() AND table_name = 'agent_task' AND column_name = 'duration_ms');
SET @ddl_task_dur = IF(@col_task_dur = 0,
    'ALTER TABLE agent_task ADD COLUMN duration_ms BIGINT DEFAULT NULL COMMENT ''任务耗时毫秒（P3.8 R9-2：本次执行 started_at→终态；人工等待不计入）'' AFTER error_msg',
    'SELECT ''agent_task.duration_ms 已存在，跳过'' AS skip_msg');
PREPARE stmt_task_dur FROM @ddl_task_dur;
EXECUTE stmt_task_dur;
DEALLOCATE PREPARE stmt_task_dur;

SET @col_step_dur = (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema = DATABASE() AND table_name = 'agent_task_step' AND column_name = 'duration_ms');
SET @ddl_step_dur = IF(@col_step_dur = 0,
    'ALTER TABLE agent_task_step ADD COLUMN duration_ms BIGINT DEFAULT NULL COMMENT ''步骤耗时毫秒（P3.8 R9-2：LLM=模型调用；TOOL=分发→回调）'' AFTER retry_count',
    'SELECT ''agent_task_step.duration_ms 已存在，跳过'' AS skip_msg');
PREPARE stmt_step_dur FROM @ddl_step_dur;
EXECUTE stmt_step_dur;
DEALLOCATE PREPARE stmt_step_dur;

-- ---------------------------------------------------------------------
-- 18. 核对：R9 的表与列已就位
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.tables
WHERE table_schema = DATABASE() AND TABLE_NAME = 'model_call_log';

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'duration_ms')
    OR (TABLE_NAME = 'agent_task_step' AND COLUMN_NAME = 'duration_ms'))
ORDER BY TABLE_NAME;

