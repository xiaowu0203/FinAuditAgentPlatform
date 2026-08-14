package com.finaudit.toolservice.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 工具调试直调请求。
 */
public record ToolExecuteRequest(
        @Schema(description = "工具入参") @NotNull(message = "入参不能为空") Map<String, Object> inputParams
) {
}
