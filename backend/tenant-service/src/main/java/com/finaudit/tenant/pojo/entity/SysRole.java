package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finaudit.tenant.pojo.dto.RoleCreateRequest;
import com.finaudit.tenant.pojo.dto.RoleUpdateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 角色（sys_role）。
 */
@Getter
@Setter
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    @Schema(description = "角色ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "角色编码（admin / auditor 等）")
    private String roleCode;

    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    /** 更新时间（P3.8 R9-2 修复 R2-10 遗留）：标 fill=INSERT_UPDATE，使 updateById/实体更新能刷新该列，
     * 否则实体里读出的旧值会被写回 SET、抑制 MySQL 的 ON UPDATE CURRENT_TIMESTAMP。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /** 由新增请求构造角色。 */
    public static SysRole from(RoleCreateRequest request, Long tenantId) {
        SysRole role = new SysRole();
        role.setTenantId(tenantId);
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName());
        return role;
    }

    /** 应用更新请求。 */
    public void apply(RoleUpdateRequest request) {
        if (request.roleName() != null) {
            this.setRoleName(request.roleName());
        }
    }
}
