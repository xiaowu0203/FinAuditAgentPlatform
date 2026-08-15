package com.finaudit.agentcore.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 报销明细项（详情响应，来源为 items JSON 列读回的 Map）。
 */
@Data
public class ReimbursementItemVO {

    @Schema(description = "明细名称")
    private String name;

    @Schema(description = "明细金额")
    private BigDecimal amount;

    @Schema(description = "金额类型")
    private String amountType;

    @Schema(description = "数量")
    private BigDecimal quantity;

    @Schema(description = "单价")
    private BigDecimal unitPrice;

    @Schema(description = "发生日期")
    private LocalDate date;

    /**
     * 由 items JSON Map 转换（Jackson 读回金额可能是 Integer/Double，统一转 Decimal）。
     */
    public static ReimbursementItemVO from(Map<String, Object> m) {
        ReimbursementItemVO vo = new ReimbursementItemVO();
        vo.setName(str(m.get("name")));
        vo.setAmount(decimal(m.get("amount")));
        vo.setAmountType(str(m.get("amountType")));
        vo.setQuantity(decimal(m.get("quantity")));
        vo.setUnitPrice(decimal(m.get("unitPrice")));
        String date = str(m.get("date"));
        vo.setDate(date == null || date.isBlank() ? null : LocalDate.parse(date));
        return vo;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(v.toString());
    }
}
