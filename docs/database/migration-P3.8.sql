-- =====================================================================
-- P3.8 增量迁移（R1：预算真实占用与释放 / R2：发票标识符入链）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3.8.sql
--
-- 背景（业务走查 B-1/B-2）：
--   budget.used_amount 此前**全仓无写入点**——注释写着"审核通过后累加（P3 审批流）"但从未实现，
--   系统只做只读预检、从不扣减。后果：同一部门同月多笔报销全部报"预算充足"、
--   全部可 AUTO_PASS/审批通过，系统一次都不拦，"预算管控"是空话。
--
-- 背景（业务走查 B-3）：
--   发票代码/号码此前在 BaiduOcrService → VatInvoiceOcr 链路里**已解析出来却被丢弃**
--   （OcrExtractTool.normalize 未纳入输出），下游查重只能靠「同申请人 + 金额完全相等 +
--   日期±30天」的启发式，产生大量误报。本阶段把票号补入归一化结果，并投影出可索引的
--   invoice_record 表（ocr_result 是 JSON，MySQL 5.7 无法对其内部字段建索引）。
--
-- 内容:
--   ① 新增 budget_occupancy 占用记账表（R1）
--   ② budget.used_amount 语义变更：由「只读种子值」变为「真实累加值」（R1，无需 DDL）
--   ③ 新增 invoice_record 发票标识符投影表（R2）
--   ④ 历史数据回填：从 expense_attachment.ocr_result JSON 抽票号投影（R2）
--
-- 幂等: CREATE TABLE IF NOT EXISTS + 回填 INSERT IGNORE；可重复执行
--
-- ⚠️ 执行顺序：①③ 必须先于 ④（回填依赖表已建出）
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

