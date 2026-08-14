package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 用户角色分配请求（替换式：以传入列表为准）。
 *
 * @param roleIds 目标角色 ID 列表（空列表即清空）
 */
public record UserRoleAssignRequest(
        @Schema(description = "目标角色 ID 列表（空列表即清空）")
        List<@NotNull(message = "角色 ID 不能为空") Long> roleIds) {
}
