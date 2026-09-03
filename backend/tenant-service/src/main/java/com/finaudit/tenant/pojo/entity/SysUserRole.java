package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户-角色关联（sys_user_role）。
 */
@Getter
@Setter
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "角色ID")
    private Long roleId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * <b>P3.5a 起不挂 {@code @TableLogic}（物理删除语义）</b>：uk_user_role(tenant_id, user_id, role_id)
     * 不含 deleted 列，逻辑删后再绑同一角色必撞唯一键（替换式绑定高频触发；P3b uk_task_step 同款坑）。
     * 纯映射表无审计需求，replaceRoles 的旧行直接物理 DELETE。deleted 列保留恒 0 兼容建表，
     * 历史 deleted=1 行由 migration-P3.5a.sql 清理。
     */
    @Schema(description = "逻辑删除列（已废弃，恒 0，兼容建表）")
    private Integer deleted;

    /** 由租户/用户/角色构造关联记录。 */
    public static SysUserRole from(Long tenantId, Long userId, Long roleId) {
        SysUserRole userRole = new SysUserRole();
        userRole.setTenantId(tenantId);
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        return userRole;
    }
}
