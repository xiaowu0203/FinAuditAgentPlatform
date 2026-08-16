package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;

/**
 * 规则校验明细项（跨服务契约）。
 * <p>来自报销单 items 快照（键 name/amount/date）或 LLM 规划入参，字段均可缺省，
 * 评估时按「缺失字段则该规则跳过该明细」处理。</p>
 *
 * @param name   明细名称
 * @param amount 明细金额
 * @param date   发生日期 YYYY-MM-DD（评估报销时效）
 */
public record RuleCheckItem(String name, BigDecimal amount, String date) {
}
