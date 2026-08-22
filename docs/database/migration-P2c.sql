-- =====================================================================
-- P2c 增量迁移（仅一次；本机已有 P2a/P2b 数据时执行，勿与 finaidit-schema.sql 重灌混用）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P2c.sql
-- 说明: 无表结构变更（报销明细为 expense_reimbursement.items JSON 内嵌，字段扩展走 JSON）；
--       变更点为种子状态/新增差旅补贴种子/rule_check 入参 schema 展开。
-- =====================================================================

USE finaudit;

-- 1. 既有种子置 published=1（进入 Nacos 生效集；此后「改→草稿(published=0)→重新发布」才脱离/回归）
UPDATE finance_rule SET published = 1 WHERE tenant_id = 1;

-- 2. 补充差旅标准 / 补贴限额种子（INSERT IGNORE 幂等；published=1 直接进生效集）
--    travel_standard: standards 数组 [{city, hotelDaily, transportTotal}]
--    subsidy_limit:   dailyAmount 单日补贴上限
INSERT IGNORE INTO finance_rule (id, tenant_id, rule_code, rule_name, rule_type, rule_config, enabled, published, version) VALUES
    (3, 1, 'travel_standard', '差旅标准', 'TRAVEL_STANDARD',
     '{"standards":[{"city":"北京","hotelDaily":500,"transportTotal":3000},{"city":"上海","hotelDaily":450,"transportTotal":2500}]}',
     1, 1, '1.0'),
    (4, 1, 'subsidy_limit', '补贴限额', 'SUBSIDY_LIMIT',
     '{"dailyAmount":200}',
     1, 1, '1.0');

-- 3. rule_check 入参 schema 展开 items 子字段（差旅/补贴评估数据源；参照 amount_verify schema 风格）
--    可选数值/整数字段声明为 ["number","null"] 等联合类型：允许前端未填（null）时传入，
--    与 RuleCheckItem「字段缺失→该规则跳过该明细」设计一致（勿改回纯 type，否则 null 直接校验失败）
UPDATE tool_registry SET input_schema = '{"type":"object","properties":{"expenseType":{"type":"string"},"claimDate":{"type":"string"},"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"amountType":{"type":["string","null"]},"quantity":{"type":["number","null"]},"unitPrice":{"type":["number","null"]},"date":{"type":["string","null"]},"city":{"type":["string","null"]},"hotelDays":{"type":["integer","null"]},"hotelAmount":{"type":["number","null"]},"transportAmount":{"type":["number","null"]},"subsidyAmount":{"type":["number","null"]}},"required":["name","amount"]}},"totalAmount":{"type":"number"}},"required":["expenseType","claimDate","items","totalAmount"]}'
WHERE tool_code = 'rule_check';

-- =====================================================================
-- 4. 同租户同 rule_type 唯一约束（防止同类型规则并存，业务层 + SQL 双层兜底）
--    设计：uk_rule_type (tenant_id, rule_type, deleted)，配合 deleted=id 软删语义：
--      - 存活行 deleted=0        → 同租户同类型天然唯一
--      - 已删行 deleted=主键id    → 各行值不同，不占唯一名额（删除实现必须 SET deleted=id，禁用写 1）
--    前置：先清理历史重复（保留最小 id 的存活行；种子每类一条，正常无重复）
--    执行：ALTER 仅一次；重复执行会报 "Duplicate key name"，勿重跑
-- =====================================================================

-- 4.1 deleted 列由 TINYINT(0/1) 扩为 BIGINT（deleted 需存主键 id；TINYINT 存 id 超 127 会溢出）
ALTER TABLE finance_rule
    MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0 未删 / 主键id 已删（配合 uk_rule_type）';

DELETE a FROM finance_rule a
    INNER JOIN finance_rule b
        ON a.tenant_id = b.tenant_id
       AND a.rule_type = b.rule_type
       AND a.id > b.id
       AND a.deleted = 0
       AND b.deleted = 0;

-- 4.2 同租户同类型唯一索引
ALTER TABLE finance_rule
    ADD UNIQUE KEY uk_rule_type (tenant_id, rule_type, deleted);
