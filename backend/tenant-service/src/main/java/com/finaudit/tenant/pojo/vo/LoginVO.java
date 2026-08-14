package com.finaudit.tenant.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录结果。
 *
 * @param token      JWT
 * @param tokenType  token 类型（Bearer）
 * @param expiresIn  有效期（秒）
 * @param user       用户信息（含角色）
 */
public record LoginVO(
        @Schema(description = "JWT") String token,
        @Schema(description = "token 类型") String tokenType,
        @Schema(description = "有效期（秒）") long expiresIn,
        @Schema(description = "用户信息（含角色）") UserInfoVO user) {
}
