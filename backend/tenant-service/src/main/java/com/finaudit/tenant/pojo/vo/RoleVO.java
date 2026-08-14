package com.finaudit.tenant.pojo.vo;

import com.finaudit.tenant.pojo.entity.SysRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 角色列表项/详情。
 *
 * @param id        角色 ID
 * @param tenantId  租户 ID
 * @param roleCode  角色编码
 * @param roleName  角色名称
 * @param createdAt 创建时间
 */
public record RoleVO(
        @Schema(description = "角色 ID") Long id,
        @Schema(description = "租户 ID") Long tenantId,
        @Schema(description = "角色编码") String roleCode,
        @Schema(description = "角色名称") String roleName,
        @Schema(description = "创建时间") LocalDateTime createdAt) {

    public static RoleVO from(SysRole role) {
        return new RoleVO(role.getId(), role.getTenantId(), role.getRoleCode(),
                role.getRoleName(), role.getCreatedAt());
    }
}
