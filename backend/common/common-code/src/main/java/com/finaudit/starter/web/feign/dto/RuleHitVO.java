package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;

/**
 * 命中的单条财务规则（跨服务契约）。
 *
 * <p><b>P3.8 R4 起补充结构化数值</b>：此前只有一句 {@code message} 文本，
 * 提交人看不出该改哪一行、改成多少（B-7）。现把「命中在哪条明细、标准值多少、实际值多少」
 * 单独作为字段返回，供 {@code ReviewFinding} 直接构造结构化问题项，
 * 避免从文本里反向解析金额（那种做法极脆弱）。</p>
 *
 * @param ruleCode  规则编码（唯一）
 * @param ruleName  规则名称
 * @param ruleType  规则类型（AMOUNT_LIMIT/REIMBURSE_EXPIRE/TRAVEL_STANDARD/SUBSIDY_LIMIT）
 * @param message   命中说明（人读，可进审核结论）
 * @param overLimit 是否属「超标/需拦截」类命中（决定审核结论是否标红）
 * @param itemIndex 命中明细行下标（0 起）；单据级命中为 null
 * @param itemName  命中明细名称；单据级命中为 null
 * @param expected  标准/限额值；不可计算时为 null
 * @param actual    实际/申报值；不可计算时为 null
 */
public record RuleHitVO(String ruleCode, String ruleName, String ruleType,
                        String message, boolean overLimit,
                        Integer itemIndex, String itemName,
                        BigDecimal expected, BigDecimal actual) {

    /**
     * 兼容构造：仅有文本说明（单据级、无可比数值）。
     * <p>保留以支持历史调用点与测试夹具，新代码应尽量带上 structured 数值。</p>
     */
    public RuleHitVO(String ruleCode, String ruleName, String ruleType, String message, boolean overLimit) {
        this(ruleCode, ruleName, ruleType, message, overLimit, null, null, null, null);
    }

    /**
     * 单据级结构化命中（如大额限额：申报总额 vs 限额）。
     */
    public static RuleHitVO ofDocument(String ruleCode, String ruleName, String ruleType, String message,
                                       boolean overLimit, BigDecimal expected, BigDecimal actual) {
        return new RuleHitVO(ruleCode, ruleName, ruleType, message, overLimit, null, null, expected, actual);
    }

    /**
     * 明细级结构化命中（如差旅住宿/交通超标、补贴超标、时效超期）。
     */
    public static RuleHitVO ofItem(String ruleCode, String ruleName, String ruleType, String message,
                                   boolean overLimit, Integer itemIndex, String itemName,
                                   BigDecimal expected, BigDecimal actual) {
        return new RuleHitVO(ruleCode, ruleName, ruleType, message, overLimit,
                itemIndex, itemName, expected, actual);
    }
}
