package com.finaudit.starter.web.feign.dto;

/**
 * 命中的单条财务规则（跨服务契约）。
 *
 * @param ruleCode  规则编码（唯一）
 * @param ruleName  规则名称
 * @param ruleType  规则类型（AMOUNT_LIMIT/REIMBURSE_EXPIRE/...）
 * @param message   命中说明（人读，可进审核结论）
 * @param overLimit 是否属「超标/需拦截」类命中（决定审核结论是否标红）
 */
public record RuleHitVO(String ruleCode, String ruleName, String ruleType,
                        String message, boolean overLimit) {
}
