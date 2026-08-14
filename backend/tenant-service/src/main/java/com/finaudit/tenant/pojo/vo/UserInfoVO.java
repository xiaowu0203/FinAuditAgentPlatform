package com.finaudit.tenant.pojo.vo;

import com.finaudit.tenant.pojo.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 当前登录用户信息（含角色编码）。
 *
 * @param id       用户 ID
 * @param tenantId 租户 ID
 * @param username 登录名
 * @param realName 真实姓名
 * @param phone    手机号
 * @param roles    角色编码列表
 */
public record UserInfoVO(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "租户 ID") Long tenantId,
        @Schema(description = "登录名") String username,
        @Schema(description = "真实姓名") String realName,
        @Schema(description = "手机号") String phone,
        @Schema(description = "角色编码列表") List<String> roles) {

    public static UserInfoVO from(SysUser user, List<String> roles) {
        return new UserInfoVO(user.getId(), user.getTenantId(), user.getUsername(),
                user.getRealName(), user.getPhone(), roles);
    }
}
