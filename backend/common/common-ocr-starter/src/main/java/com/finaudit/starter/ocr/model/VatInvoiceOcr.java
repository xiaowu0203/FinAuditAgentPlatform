package com.finaudit.starter.ocr.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 增值税发票识别（vat_invoice）细节字段，作「智能财务票据」的税号/金额细节兜底。
 * <p>字段名对齐百度官方：{@code AmountInFiguers}（价税合计小写，含千分位逗号，解析时已清洗）、
 * {@code InvoiceDate}（YYYYMMDD，已转 LocalDate）、{@code SellerRegisterNum}（销售方统一社会信用代码/税号）、
 * {@code SellerName}（销售方名称=商户）。金额一律 {@link BigDecimal}（CLAUDE.md §5.3）。</p>
 */
@Data
public class VatInvoiceOcr {

    /** 价税合计（小写），千分位逗号已去除 */
    private BigDecimal amountInFiguers;
    /** 开票日期 */
    private LocalDate invoiceDate;
    /** 销售方名称（商户） */
    private String sellerName;
    /** 销售方税号（SellerRegisterNum） */
    private String sellerRegisterNum;
    /** 发票代码 */
    private String invoiceCode;
    /** 发票号码 */
    private String invoiceNum;
    /** 发票类型（如 电子发票） */
    private String invoiceType;
    /** 金额 */
    private BigDecimal totalAmount;
    /** 税额 */
    private BigDecimal totalTax;

}
