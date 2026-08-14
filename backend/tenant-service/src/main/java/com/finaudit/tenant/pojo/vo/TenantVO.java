package com.finaudit.tenant.pojo.vo;

import com.finaudit.tenant.pojo.entity.SysTenant;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 租户列表项/详情。
 *
 * @param id        租户 ID
 * @param tenantCode 租户编码
 * @param tenantName 租户名称
 * @param status     状态（1启用 0禁用）
 * @param createdAt  创建时间
 */
public record TenantVO(
        @Schema(description = "租户 ID") Long id,
        @Schema(description = "租户编码") String tenantCode,
        @Schema(description = "租户名称") String tenantName,
        @Schema(description = "状态: 1启用 0禁用") Integer status,
        @Schema(description = "创建时间") LocalDateTime createdAt) {

    public static TenantVO from(SysTenant tenant) {
        return new TenantVO(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                tenant.getStatus(), tenant.getCreatedAt());
    }
}
