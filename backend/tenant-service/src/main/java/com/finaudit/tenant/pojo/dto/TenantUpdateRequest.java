package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 更新租户请求。
 *
 * @param tenantName 租户名称
 * @param status     状态（1启用 0禁用）
 */
public record TenantUpdateRequest(
        @Size(max = 64, message = "租户名称最长 64 字符")
        @Schema(description = "租户名称")
        String tenantName,

        @Schema(description = "状态: 1启用 0禁用")
        Integer status) {
}
