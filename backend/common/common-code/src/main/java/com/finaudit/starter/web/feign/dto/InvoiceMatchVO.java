package com.finaudit.starter.web.feign.dto;

import java.util.List;

/**
 * 票据-明细交叉核验结果（跨服务契约，P3.8 R3）。
 *
 * <p>由 agent-core 的 {@code invoice_match} 数据装配 + tool-service 的
 * {@code InvoiceMatchTool} 比对产出。用于回答两个问题：</p>
 * <ul>
 *   <li><b>票据与明细是否对得上</b>：票面金额合计是否与申报明细存在明显矛盾
 *       （明细总额显著大于票面合计 ⇒ 可能虚报；明细中存在单笔超过票面合计的项 ⇒ 明细与票据不符）。</li>
 *   <li><b>票据自身是否合规</b>：发票代码位数、税号形态、开票日期合理性（离线规则验真）。</li>
 * </ul>
 *
 * @param invoiceCount 本单发票条数
 * @param itemCount    申报明细条数
 * @param invoiceTotal 票面金额合计
 * @param claimTotal   申报明细金额合计
 * @param gap          差额 = 申报合计 − 票面合计（正数表示申报多于票据）
 * @param consistent   true=票据与明细一致（差额在容差内且无单笔越界）
 * @param flags        异常清单（code/message 结构），无异常为空列表
 */
public record InvoiceMatchVO(int invoiceCount, int itemCount,
                             java.math.BigDecimal invoiceTotal, java.math.BigDecimal claimTotal,
                             java.math.BigDecimal gap, boolean consistent,
                             List<Flag> flags) {

    /**
     * 单条异常。
     *
     * @param code    异常编码（如 AMOUNT_MISMATCH / INVOICE_CODE_FORMAT）
     * @param message 可读说明
     */
    public record Flag(String code, String message) {
    }

    public static InvoiceMatchVO empty() {
        return new InvoiceMatchVO(0, 0, null, null, null, true, List.of());
    }
}
