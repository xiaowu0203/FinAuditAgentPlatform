package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 财务规则校验请求（跨服务契约，tool-service → agent-core 委托评估）。
 *
 * @param expenseType 费用类型（TRAVEL/ENTERTAINMENT/OFFICE）
 * @param claimDate   报销日期 YYYY-MM-DD（评估报销时效的基准）
 * @param items       报销明细（含发生日期，评估时效/限额）
 * @param totalAmount 申报总额
 */
public record RuleCheckRequest(String expenseType, String claimDate,
                               List<RuleCheckItem> items, BigDecimal totalAmount) {
}
