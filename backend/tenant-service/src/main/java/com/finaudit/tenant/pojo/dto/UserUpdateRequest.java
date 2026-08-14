package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 更新用户请求。
 *
 * @param realName 真实姓名
 * @param phone    手机号
 * @param status   状态（1启用 0禁用）
 * @param password 新密码（非空才重置，BCrypt 落库）
 */
public record UserUpdateRequest(
        @Schema(description = "真实姓名")
        String realName,

        @Schema(description = "手机号")
        String phone,

        @Schema(description = "状态: 1启用 0禁用")
        Integer status,

        @Size(min = 6, max = 32, message = "密码长度 6-32")
        @Schema(description = "新密码（非空才重置）")
        String password) {
}
