package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报销明细项（提交请求）。
 *
 * @param name       明细名称
 * @param amount     明细金额（Decimal 强制）
 * @param amountType 金额类型（可空，如 高铁/住宿）
 * @param quantity   数量（可空）
 * @param unitPrice  单价（可空）
 * @param date       发生日期（可空）
 */
public record ReimbursementItemRequest(
        @NotBlank(message = "明细名称不能为空") String name,
        @NotNull(message = "明细金额不能为空") BigDecimal amount,
        @Schema(description = "金额类型") String amountType,
        @Schema(description = "数量") BigDecimal quantity,
        @Schema(description = "单价") BigDecimal unitPrice,
        @Schema(description = "发生日期") LocalDate date) {
}
