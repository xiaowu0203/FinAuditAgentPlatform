package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 审批动作请求（approve / reject / amend / terminate 共用）。
 *
 * @param comment 审批意见（可空，max 512）
 * @param items   修正后明细（仅 amend 必填；服务端 Σitems 重算总额，不信任客户端）
 */
public record AuditActionRequest(
        @Size(max = 512, message = "审批意见不能超过 512 字符")
        @Schema(description = "审批意见") String comment,
        @Valid
        @Schema(description = "修正后明细（仅 amend 必填，服务端重算总额）") List<ReimbursementItemRequest> items) {
}
