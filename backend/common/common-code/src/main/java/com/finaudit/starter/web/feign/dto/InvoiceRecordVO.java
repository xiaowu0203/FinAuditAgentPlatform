package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;

/**
 * 发票标识符投影（跨服务契约，P3.8 R3）。
 *
 * <p>由 agent-core 的 {@code invoice_record} 投影表读出，供 tool-service 的
 * {@code invoice_match} 工具做「票据-明细交叉核验」与「离线规则验真」。</p>
 *
 * @param invoiceCode  发票代码（缺失为空串）
 * @param invoiceNum   发票号码（判重主键，缺失为空串）
 * @param sellerTaxNo  销售方税号（统一社会信用代码）
 * @param amount       票面金额（价税合计）
 * @param invDate      开票日期 YYYY-MM-DD
 * @param reimbId      归属报销单ID
 */
public record InvoiceRecordVO(String invoiceCode, String invoiceNum, String sellerTaxNo,
                              BigDecimal amount, String invDate, Long reimbId) {
}
