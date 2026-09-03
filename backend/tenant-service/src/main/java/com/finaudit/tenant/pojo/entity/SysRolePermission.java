package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色-权限映射（sys_role_permission，P3.5a）：角色是权限的分配单位，替换式分配。
 * <p><b>不挂 {@code @TableLogic}（物理删除语义）</b>：uk_role_perm(tenant_id, role_id, perm_id)
 * 不含 deleted 列，逻辑删后再绑同一权限必撞唯一键（P3b uk_task_step 同款坑）；
 * 纯映射表无审计需求，替换式分配的旧行直接物理 DELETE。deleted 列保留恒 0 兼容建表。</p>
 */
@Getter
@Setter
@TableName("sys_role_permission")
public class SysRolePermission {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "角色ID")
    private Long roleId;

    @Schema(description = "权限ID")
    private Long permId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 由角色分配请求构造映射行（批量新增用）。
     *
     * @param tenantId 租户（取上下文，不信任请求体）
     * @param roleId   角色 ID
     * @param permId   权限 ID
     */
    public static SysRolePermission from(Long tenantId, Long roleId, Long permId) {
        SysRolePermission rp = new SysRolePermission();
        rp.setTenantId(tenantId);
        rp.setRoleId(roleId);
        rp.setPermId(permId);
        return rp;
    }

    /**
     * 由权限 ID 列表构造映射行集（空列表返回空集）。
     */
    public static List<SysRolePermission> listFrom(Long tenantId, Long roleId, List<Long> permIds) {
        if (permIds == null || permIds.isEmpty()) {
            return List.of();
        }
        return permIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(permId -> from(tenantId, roleId, permId))
                .toList();
    }
}
