package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;

/**
 * 疑似重复的历史报销单（跨服务契约）。
 *
 * <p><b>P3.8 R3 引入分级</b>（业务走查 B-4）：此前的判定只有「金额完全相等 + 申请人相同 +
 * 日期±30 天」再叠加商户近似，命中即判疑似重复，把正常单据误判（R1 联调已实测复现）。
 * 现改为两级：</p>
 * <ul>
 *   <li>{@link #LEVEL_HIGH} —— <b>发票号硬命中</b>：同一张票（发票代码+号码）已存在于其他报销单。
 *       这是客观唯一标识，可据此认定重复入账。</li>
 *   <li>{@link #LEVEL_MEDIUM} —— 金额相同 + 商户相同（或双侧商户均缺失）+ 日期在 ±30 天内。
 *       只是「可能是重复」，需人工判断，<b>不应自动触发风控命中</b>。</li>
 * </ul>
 *
 * @param reimbId        历史报销单ID
 * @param reimbNo        历史报销单号
 * @param title          历史单据标题
 * @param totalAmount    历史申报总额
 * @param claimDate      历史报销日期 YYYY-MM-DD
 * @param merchant       历史票据商户（双侧 OCR 都有 merchant 才有值）
 * @param merchantMatched 商户是否匹配（两侧商户均可得时精确匹配；否则按金额+日期近似）
 * @param dupLevel       重复等级：{@link #LEVEL_HIGH} / {@link #LEVEL_MEDIUM}
 * @param invoiceCode    命中的发票代码（仅 HIGH 级有值）
 * @param invoiceNum     命中的发票号码（仅 HIGH 级有值）
 */
public record DuplicateItemVO(Long reimbId, String reimbNo, String title,
                              BigDecimal totalAmount, String claimDate,
                              String merchant, boolean merchantMatched,
                              String dupLevel, String invoiceCode, String invoiceNum) {

    /** 高置信：发票号硬命中（同一张票已报销） */
    public static final String LEVEL_HIGH = "LEVEL_HIGH";
    /** 中置信：金额+商户+日期区间近似，需人工判断 */
    public static final String LEVEL_MEDIUM = "LEVEL_MEDIUM";

    /**
     * 中置信条目（向后兼容构造：不带票号）。
     */
    public static DuplicateItemVO medium(Long reimbId, String reimbNo, String title,
                                         BigDecimal totalAmount, String claimDate,
                                         String merchant, boolean merchantMatched) {
        return new DuplicateItemVO(reimbId, reimbNo, title, totalAmount, claimDate,
                merchant, merchantMatched, LEVEL_MEDIUM, null, null);
    }

    /**
     * 高置信条目（发票号硬命中）。
     */
    public static DuplicateItemVO high(Long reimbId, String reimbNo, String title,
                                       BigDecimal totalAmount, String claimDate,
                                       String merchant, String invoiceCode, String invoiceNum) {
        return new DuplicateItemVO(reimbId, reimbNo, title, totalAmount, claimDate,
                merchant, true, LEVEL_HIGH, invoiceCode, invoiceNum);
    }

    /** 是否为发票号硬命中 */
    public boolean isHigh() {
        return LEVEL_HIGH.equals(dupLevel);
    }
}
