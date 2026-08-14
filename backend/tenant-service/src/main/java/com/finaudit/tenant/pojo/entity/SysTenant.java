package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finaudit.tenant.pojo.dto.TenantCreateRequest;
import com.finaudit.tenant.pojo.dto.TenantUpdateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 租户（sys_tenant）。
 * <p>租户主数据，本身无 tenant_id 列（多租户拦截器已 ignore）。</p>
 */
@Getter
@Setter
@TableName("sys_tenant")
public class SysTenant {

    @TableId(type = IdType.AUTO)
    @Schema(description = "租户ID")
    private Long id;

    @Schema(description = "租户编码")
    private String tenantCode;

    @Schema(description = "租户名称")
    private String tenantName;

    @Schema(description = "状态: 1启用 0禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /** 由新增请求构造新租户（默认启用）。 */
    public static SysTenant from(TenantCreateRequest request) {
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(request.tenantCode());
        tenant.setTenantName(request.tenantName());
        tenant.setStatus(request.status() == null ? 1 : request.status());
        return tenant;
    }

    /** 应用更新请求。 */
    public void apply(TenantUpdateRequest request) {
        if (request.tenantName() != null) {
            this.setTenantName(request.tenantName());
        }
        if (request.status() != null) {
            this.setStatus(request.status());
        }
    }
}
