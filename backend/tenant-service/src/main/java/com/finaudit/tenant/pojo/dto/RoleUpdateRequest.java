package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 更新角色请求。
 *
 * @param roleName 角色名称
 */
public record RoleUpdateRequest(
        @Size(max = 64, message = "角色名称最长 64 字符")
        @Schema(description = "角色名称")
        String roleName) {
}
