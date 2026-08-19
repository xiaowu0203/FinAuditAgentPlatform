package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 报销单修改重跑请求（P3b 工作流重设计：提交人改明细后同单续跑）。
 * <p>与 {@link ReimbursementSubmitRequest} 的区别：<b>无 title / deptName</b>——
 * 标题与部门由服务端强制沿用库内旧值，不接受请求体修改（用户确认决策 2）；其余字段可全量修改。</p>
 *
 * @param expenseType   费用类型（TRAVEL/ENTERTAINMENT/OFFICE，可改）
 * @param claimDate     报销日期（可改）
 * @param remark        备注（可空，可改）
 * @param items         报销明细（总金额由服务端求和，不信任客户端）
 * @param fileRecordIds 附件 file_record id 列表（可改；移除项解绑、新增项重新绑定）
 */
public record ReimbursementResubmitRequest(
        @NotBlank(message = "费用类型不能为空") String expenseType,
        @NotNull(message = "报销日期不能为空") LocalDate claimDate,
        @Schema(description = "备注") String remark,
        @NotEmpty(message = "报销明细不能为空") @Valid List<ReimbursementItemRequest> items,
        @NotEmpty(message = "请上传附件") List<Long> fileRecordIds) {
}
