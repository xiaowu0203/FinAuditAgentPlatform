package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 票据-明细交叉核验请求（跨服务契约，P3.8 R3）。
 *
 * <p>tool-service 的 {@code invoice_match} 工具装配入参，agent-core 侧按 reimbId
 * 取发票投影后执行比对与离线验真。</p>
 *
 * @param items        申报明细（元素为含 name/amount 的 Map，与 rule_check 同构）
 * @param claimedTotal 申报明细合计（可为空，为空时由服务端按 items 求和）
 */
public record InvoiceMatchRequest(List<Map<String, Object>> items, BigDecimal claimedTotal) {
}
