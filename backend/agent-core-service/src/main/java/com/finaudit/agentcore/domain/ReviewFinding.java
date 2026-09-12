package com.finaudit.agentcore.domain;

import java.math.BigDecimal;

/**
 * 结构化审核问题项（P3.8 R4-1，业务走查 B-7）。
 *
 * <p><b>为什么要结构化</b>：现状驳回只给一句 {@code risk_desc} 文字（如「规则校验超标」），
 * 提交人不知道该改哪一行、改成多少。本结构把问题定位到<b>明细行</b>并给出
 * 「期望值 / 实际值 / 差额 / 修改建议」，前端可据此在编辑页标红对应行并预填建议值，
 * 直接支撑「驳回重提引导」这一业务目标。</p>
 *
 * <p><b>字段可空性</b>：只有限额/标准类问题才有 {@code expected}（标准值）与 {@code gap}（差额）；
 * 如「疑似重复报销」「发票号硬命中」这类问题没有可比的标准值，对应字段为空。
 * {@code itemIndex} 为明细行下标（0 起），非明细级问题（如预算超支、风控存疑）为空。</p>
 *
 * @param code       问题编码（稳定标识，供前端映射与埋点，如 AMOUNT_LIMIT / DUPLICATE_INVOICE）
 * @param level      问题级别，同时决定工单 triggerType（见 {@link #LEVEL_OVER_LIMIT} 等）
 * @param itemIndex  关联明细行下标（0 起）；非明细级问题为 null
 * @param itemName   关联明细名称；非明细级问题为 null
 * @param expected   期望值（标准/限额）；无标准值时为 null
 * @param actual     实际值（申报值）；无可比实际值时为 null
 * @param gap        差额 = actual − expected（正数表示超出）；不可计算时为 null
 * @param suggestion 修改建议（人读，供前端提示与工单展示）
 */
public record ReviewFinding(String code, String level, Integer itemIndex, String itemName,
                            BigDecimal expected, BigDecimal actual, BigDecimal gap,
                            String suggestion) {

    /** 大额超限：财务规则 AMOUNT_LIMIT 命中 → 工单 triggerType=OVER_LIMIT */
    public static final String LEVEL_OVER_LIMIT = "OVER_LIMIT";
    /** 规则性失败：超标/预算不足/票据与明细不一致 → triggerType=RULE_FAIL */
    public static final String LEVEL_RULE_FAIL = "RULE_FAIL";
    /** 风控存疑：重复报销/金额不符/LLM 置信度低 → triggerType=RISK_HIT */
    public static final String LEVEL_RISK_HIT = "RISK_HIT";
    /** LLM 汇总结论非 APPROVE 的问题编码（沿用其作为 reason 前缀，见 {@link #toReason()}） */
    public static final String CODE_LLM_DECISION = "LLM_DECISION";

    /**
     * 明细级问题（带行定位与标准差额）。
     *
     * @param code       问题编码
     * @param level      问题级别
     * @param itemIndex  明细行下标（0 起）
     * @param itemName   明细名称
     * @param expected   标准值
     * @param actual     实际值
     * @param suggestion 修改建议
     */
    public static ReviewFinding ofItem(String code, String level, Integer itemIndex, String itemName,
                                       BigDecimal expected, BigDecimal actual, String suggestion) {
        BigDecimal gap = (expected != null && actual != null) ? actual.subtract(expected) : null;
        return new ReviewFinding(code, level, itemIndex, itemName, expected, actual, gap, suggestion);
    }

    /**
     * 单据级问题（无明细行定位）。
     */
    public static ReviewFinding ofDocument(String code, String level, String suggestion) {
        return new ReviewFinding(code, level, null, null, null, null, null, suggestion);
    }

    /**
     * 单据级问题（带金额对比，如票面合计 vs 申报合计）。
     */
    public static ReviewFinding ofDocumentAmount(String code, String level,
                                                 BigDecimal expected, BigDecimal actual, String suggestion) {
        BigDecimal gap = (expected != null && actual != null) ? actual.subtract(expected) : null;
        return new ReviewFinding(code, level, null, null, expected, actual, gap, suggestion);
    }

    /**
     * 生成本结构对应的复核原因串（兼容既有 {@code review_reasons} 与 trigger_type 前缀约定）。
     * <p>格式 {@code "{LEVEL}:{说明}"}，说明优先带上明细定位与差额，便于人工快速判断。</p>
     * <p><b>特例</b>：{@link #CODE_LLM_DECISION} 沿用 {@code LLM_DECISION} 作前缀——
     * 它是 {@code FlowDecision} 文档化的既有前缀，既有消费方依赖该格式，
     * 不能因为结构化改造顺手改掉（该 code 仍按 RISK_HIT 级别参与工单 triggerType 解析）。</p>
     */
    public String toReason() {
        String prefix = CODE_LLM_DECISION.equals(code) ? CODE_LLM_DECISION : level;
        StringBuilder sb = new StringBuilder(prefix).append(':');
        if (itemName != null && !itemName.isBlank()) {
            sb.append(itemName);
            if (itemIndex != null) {
                sb.append("(第").append(itemIndex + 1).append("行)");
            }
            sb.append(' ');
        }
        if (expected != null && actual != null) {
            sb.append("实际 ").append(actual.stripTrailingZeros().toPlainString())
                    .append(" / 标准 ").append(expected.stripTrailingZeros().toPlainString());
            if (gap != null && gap.signum() > 0) {
                sb.append("，超 ").append(gap.stripTrailingZeros().toPlainString());
            }
        } else if (suggestion != null && !suggestion.isBlank()) {
            sb.append(suggestion);
        } else {
            sb.append(code);
        }
        return sb.toString();
    }
}
