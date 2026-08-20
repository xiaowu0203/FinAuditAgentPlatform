package com.finaudit.tenant.pojo.vo;

import com.finaudit.starter.web.mask.annotation.Mask;
import com.finaudit.starter.web.mask.enums.MaskType;
import com.finaudit.tenant.pojo.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 用户列表项。
 *
 * @param id        用户 ID
 * @param tenantId  租户 ID
 * @param username  登录名
 * @param realName  真实姓名
 * @param phone     手机号（对外脱敏）
 * @param status    状态（1启用 0禁用）
 * @param createdAt 创建时间
 */
public record UserVO(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "租户 ID") Long tenantId,
        @Schema(description = "登录名") String username,
        @Schema(description = "真实姓名") String realName,
        @Mask(MaskType.PHONE) @Schema(description = "手机号") String phone,
        @Schema(description = "状态: 1启用 0禁用") Integer status,
        @Schema(description = "创建时间") LocalDateTime createdAt) {

    public static UserVO from(SysUser user) {
        return new UserVO(user.getId(), user.getTenantId(), user.getUsername(),
                user.getRealName(), user.getPhone(), user.getStatus(), user.getCreatedAt());
    }
}
