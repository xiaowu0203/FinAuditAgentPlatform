package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * @param username   登录名
 * @param password   密码
 * @param tenantCode 租户编码（为空默认 default）
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Schema(description = "登录名")
        String username,

        @NotBlank(message = "密码不能为空")
        @Schema(description = "密码")
        String password,

        @Schema(description = "租户编码（为空默认 default）")
        String tenantCode) {
}
