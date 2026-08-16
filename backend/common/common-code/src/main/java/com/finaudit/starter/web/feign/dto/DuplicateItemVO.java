package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;

/**
 * 疑似重复的历史报销单（跨服务契约）。
 *
 * @param reimbId        历史报销单ID
 * @param reimbNo        历史报销单号
 * @param title          历史单据标题
 * @param totalAmount    历史申报总额
 * @param claimDate      历史报销日期 YYYY-MM-DD
 * @param merchant       历史票据商户（双侧 OCR 都有 merchant 才有值）
 * @param merchantMatched 商户是否匹配（两侧商户均可得时精确匹配；否则按金额+日期近似）
 */
public record DuplicateItemVO(Long reimbId, String reimbNo, String title,
                              BigDecimal totalAmount, String claimDate,
                              String merchant, boolean merchantMatched) {
}
