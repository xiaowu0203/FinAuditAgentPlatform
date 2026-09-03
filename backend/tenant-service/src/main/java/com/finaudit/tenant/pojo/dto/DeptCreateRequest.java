package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增部门请求（P3.5b）。
 *
 * @param deptName 部门名称（租户内唯一）
 * @param parentId 父部门 ID（空/0 为根）
 */
public record DeptCreateRequest(
        @NotBlank(message = "部门名称不能为空")
        @Size(max = 64, message = "部门名称最长 64 字符")
        @Schema(description = "部门名称（租户内唯一）")
        String deptName,

        @Schema(description = "父部门 ID（空/0 为根）")
        Long parentId) {
}