package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 财务规则新增/修改请求（P2c 规则配置）。
 * <p>ruleConfig 为结构化 JSON，按 ruleType 承载：AMOUNT_LIMIT→threshold、REIMBURSE_EXPIRE→maxDays、
 * TRAVEL_STANDARD→standards（城市标准数组）、SUBSIDY_LIMIT→dailyAmount；金额/数值一律 Decimal。</p>
 *
 * @param ruleCode   规则编码（创建后不可改，唯一）
 * @param ruleName   规则名称
 * @param ruleType   规则类型（AMOUNT_LIMIT/REIMBURSE_EXPIRE/TRAVEL_STANDARD/SUBSIDY_LIMIT）
 * @param ruleConfig 结构化规则配置（可空，空则默认空对象）
 * @param enabled    启停: 1启用 0禁用（可空，空则默认 1）
 */
public record RuleSaveRequest(
        @NotBlank(message = "规则编码不能为空")
        @Size(max = 64, message = "规则编码最长 64 字符")
        String ruleCode,

        @NotBlank(message = "规则名称不能为空")
        @Size(max = 64, message = "规则名称最长 64 字符")
        String ruleName,

        @NotBlank(message = "规则类型不能为空")
        @Size(max = 32, message = "规则类型最长 32 字符")
        String ruleType,

        @Schema(description = "结构化规则配置（如 {\"threshold\":5000} / {\"maxDays\":30} / {\"standards\":[...]} / {\"dailyAmount\":200}）")
        Map<String, Object> ruleConfig,

        @Schema(description = "启停: 1启用 0禁用，空默认 1")
        Integer enabled) {
}
