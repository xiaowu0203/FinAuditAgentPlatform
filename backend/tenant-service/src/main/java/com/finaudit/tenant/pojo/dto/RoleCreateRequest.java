package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增角色请求。
 * <p>所属租户取请求上下文（X-Tenant-Id），不信任请求体。</p>
 *
 * @param roleCode 角色编码（同租户唯一）
 * @param roleName 角色名称
 */
public record RoleCreateRequest(
        @NotBlank(message = "角色编码不能为空")
        @Size(max = 32, message = "角色编码最长 32 字符")
        @Schema(description = "角色编码（同租户唯一）")
        String roleCode,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称最长 64 字符")
        @Schema(description = "角色名称")
        String roleName) {
}
