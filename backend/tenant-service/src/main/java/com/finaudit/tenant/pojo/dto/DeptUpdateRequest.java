package com.finaudit.tenant.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 部门更新请求（P3.5b 树形 CRUD；仅提交的字段生效）。
 * <p>parentId 变更走防环校验：不能把自己挂到自身子孙下。</p>
 *
 * @param deptName 部门名称（租户内唯一；null 不改）
 * @param parentId 父部门 ID（null 不改；0=根）
 * @param status   状态 1启用 0停用（null 不改）
 */
public record DeptUpdateRequest(
        @Size(max = 64, message = "部门名称最长 64 字符")
        @Schema(description = "部门名称（null 不改）")
        String deptName,

        @Schema(description = "父部门 ID（null 不改；0=根）")
        Long parentId,

        @Schema(description = "状态 1启用 0停用（null 不改）")
        Integer status) {
}