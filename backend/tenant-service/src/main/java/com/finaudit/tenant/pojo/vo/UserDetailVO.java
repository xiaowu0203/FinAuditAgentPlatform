package com.finaudit.tenant.pojo.vo;

import com.finaudit.starter.web.mask.annotation.Mask;
import com.finaudit.starter.web.mask.enums.MaskType;
import com.finaudit.tenant.pojo.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户详情（含角色）。
 *
 * @param id        用户 ID
 * @param tenantId  租户 ID
 * @param username  登录名
 * @param realName  真实姓名
 * @param phone     手机号（对外脱敏）
 * @param deptId    部门 ID（P3.5b；未绑定为 null）
 * @param deptName  部门名称（P3.5b）
 * @param status    状态（1启用 0禁用）
 * @param createdAt 创建时间
 * @param roles     角色列表
 */
public record UserDetailVO(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "租户 ID") Long tenantId,
        @Schema(description = "登录名") String username,
        @Schema(description = "真实姓名") String realName,
        @Mask(MaskType.PHONE) @Schema(description = "手机号") String phone,
        @Schema(description = "部门 ID") Long deptId,
        @Schema(description = "部门名称") String deptName,
        @Schema(description = "状态: 1启用 0禁用") Integer status,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "角色列表") List<RoleVO> roles) {

    public static UserDetailVO from(SysUser user, List<RoleVO> roles, String deptName) {
        return new UserDetailVO(user.getId(), user.getTenantId(), user.getUsername(),
                user.getRealName(), user.getPhone(), user.getDeptId(),
                deptName, user.getStatus(), user.getCreatedAt(), roles);
    }
}
