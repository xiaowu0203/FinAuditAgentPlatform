package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新增用户请求。
 * <p>所属租户取请求上下文（X-Tenant-Id），不信任请求体。</p>
 *
 * @param username 登录名
 * @param password 密码（BCrypt 落库）
 * @param realName 真实姓名
 * @param phone    手机号
 * @param deptId   部门 ID（P3.5b，可为空）
 * @param status   状态（1启用 0禁用，为空默认 1）
 * @param roleIds  待绑定角色 ID 列表（可为空）
 */
public record UserCreateRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名最长 64 字符")
        @Schema(description = "登录名")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度 6-32")
        @Schema(description = "密码")
        String password,

        @Schema(description = "真实姓名")
        String realName,

        @Schema(description = "手机号")
        String phone,

        @Schema(description = "部门 ID（员工级归属，P3.5b）")
        Long deptId,

        @Schema(description = "状态: 1启用 0禁用（默认 1）")
        Integer status,

        @Schema(description = "待绑定角色 ID 列表")
        List<@NotNull(message = "角色 ID 不能为空") Long> roleIds) {
}
