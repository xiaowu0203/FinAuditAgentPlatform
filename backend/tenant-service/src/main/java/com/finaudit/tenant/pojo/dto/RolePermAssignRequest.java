package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色权限分配请求（替换式：以传入列表为准，P3.5a）。
 *
 * @param permIds 目标权限 ID 列表（空列表即清空）
 */
public record RolePermAssignRequest(
        @Schema(description = "目标权限 ID 列表（空列表即清空）")
        List<@NotNull(message = "权限 ID 不能为空") Long> permIds) {
}
