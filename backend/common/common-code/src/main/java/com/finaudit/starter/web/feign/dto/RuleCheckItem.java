package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;

/**
 * 规则校验明细项（跨服务契约）。
 * <p>来自报销单 items 快照（键 name/amount/date）或 LLM 规划入参，字段均可缺省，
 * 评估时按「缺失字段则该规则跳过该明细」处理。</p>
 * <p>P2c 起为差旅标准/补贴限额扩展：city 城市、hotelDays 住宿天数、hotelAmount 住宿总金额、
 * transportAmount 交通总金额、subsidyAmount 补贴金额，均可选——明细未提供时 TRAVEL_STANDARD /
 * SUBSIDY_LIMIT 评估跳过该明细（优雅降级）。</p>
 *
 * @param name            明细名称
 * @param amount          明细金额
 * @param date            发生日期 YYYY-MM-DD（评估报销时效）
 * @param city            城市（差旅住宿/交通标准）
 * @param hotelDays       住宿天数（差旅住宿超标 = hotelAmount / hotelDays &gt; hotelDaily）
 * @param hotelAmount     住宿总金额（Decimal）
 * @param transportAmount 交通总金额（Decimal）
 * @param subsidyAmount   补贴金额（补贴限额 = subsidyAmount / hotelDays &gt; dailyAmount）
 */
public record RuleCheckItem(String name, BigDecimal amount, String date,
                            String city, Integer hotelDays,
                            BigDecimal hotelAmount, BigDecimal transportAmount,
                            BigDecimal subsidyAmount) {
}
