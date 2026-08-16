package com.finaudit.toolservice.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 工具注册请求。
 */
public record ToolRegistryRegisterRequest(
        @Schema(description = "工具编码") @NotBlank(message = "工具编码不能为空") String toolCode,
        @Schema(description = "工具名称") @NotBlank(message = "工具名称不能为空") String toolName,
        @Schema(description = "工具描述") String description,
        @Schema(description = "入参 JSON Schema（强校验）") @NotNull(message = "入参 Schema 不能为空") Map<String, Object> inputSchema,
        @Schema(description = "是否启用（0 禁用 / 1 启用，缺省 1）") Integer enabled,
        @Schema(description = "工具版本（缺省 1.0）") String version,
        @Schema(description = "业务场景（FINANCE/GENERIC，缺省 FINANCE）") String scenario,
        @Schema(description = "结果缓存开关（1 缓存 / 0 不缓存，缺省 1）") Integer cacheable
) {
}
