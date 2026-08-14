package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增租户请求。
 *
 * @param tenantCode 租户编码（唯一）
 * @param tenantName 租户名称
 * @param status     状态（1启用 0禁用，为空默认 1）
 */
public record TenantCreateRequest(
        @NotBlank(message = "租户编码不能为空")
        @Size(max = 32, message = "租户编码最长 32 字符")
        @Schema(description = "租户编码（唯一）")
        String tenantCode,

        @NotBlank(message = "租户名称不能为空")
        @Size(max = 64, message = "租户名称最长 64 字符")
        @Schema(description = "租户名称")
        String tenantName,

        @Schema(description = "状态: 1启用 0禁用（默认 1）")
        Integer status) {
}
