package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 报销单提交请求（明细 + 附件文件 ID 列表）。
 *
 * @param title         报销标题
 * @param expenseType   费用类型（TRAVEL/ENTERTAINMENT/OFFICE）
 * @param deptName      部门（D6：先字符串）
 * @param claimDate     报销日期
 * @param remark        备注（可空）
 * @param items         报销明细（总金额由服务端求和，不信任客户端）
 * @param fileRecordIds file_record id 列表（经 file-service 上传获取；须归属当前租户且未被其他报销单关联）
 */
public record ReimbursementSubmitRequest(
        @NotBlank(message = "报销标题不能为空") String title,
        @NotBlank(message = "费用类型不能为空") String expenseType,
        @NotBlank(message = "部门不能为空") String deptName,
        @NotNull(message = "报销日期不能为空") LocalDate claimDate,
        @Schema(description = "备注") String remark,
        @NotEmpty(message = "报销明细不能为空") @Valid List<ReimbursementItemRequest> items,
        @NotEmpty(message = "请上传附件") List<Long> fileRecordIds) {
}
