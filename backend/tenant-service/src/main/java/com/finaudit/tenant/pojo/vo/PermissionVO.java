package com.finaudit.tenant.pojo.vo;

import com.finaudit.tenant.pojo.entity.SysPermission;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 权限目录项（分配界面展示）。
 */
public record PermissionVO(
        @Schema(description = "权限ID") Long id,
        @Schema(description = "权限标识符") String permCode,
        @Schema(description = "权限名称") String permName,
        @Schema(description = "类型: MENU/API") String permType,
        @Schema(description = "分组") String groupName) {

    public static PermissionVO from(SysPermission permission) {
        return new PermissionVO(permission.getId(), permission.getPermCode(),
                permission.getPermName(), permission.getPermType(), permission.getGroupName());
    }
}
