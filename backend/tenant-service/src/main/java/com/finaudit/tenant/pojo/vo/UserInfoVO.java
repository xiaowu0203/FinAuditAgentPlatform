package com.finaudit.tenant.pojo.vo;

import com.finaudit.starter.web.mask.annotation.Mask;
import com.finaudit.starter.web.mask.enums.MaskType;
import com.finaudit.tenant.pojo.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Set;

/**
 * 当前登录用户信息（含角色编码与权限标识符，P3.5）。
 *
 * @param id       用户 ID
 * @param tenantId 租户 ID
 * @param username 登录名
 * @param realName 真实姓名
 * @param phone    手机号（对外脱敏）
 * @param roles    角色编码列表
 * @param perms    权限标识符列表（前端菜单/按钮/路由动态渲染依据）
 */
public record UserInfoVO(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "租户 ID") Long tenantId,
        @Schema(description = "登录名") String username,
        @Schema(description = "真实姓名") String realName,
        @Mask(MaskType.PHONE) @Schema(description = "手机号") String phone,
        @Schema(description = "角色编码列表") List<String> roles,
        @Schema(description = "权限标识符列表") List<String> perms) {

    public static UserInfoVO from(SysUser user, List<String> roles, Set<String> perms) {
        return new UserInfoVO(user.getId(), user.getTenantId(), user.getUsername(),
                user.getRealName(), user.getPhone(), roles,
                perms == null ? List.of() : List.copyOf(perms));
    }
}
