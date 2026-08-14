package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 任务提交请求。
 */
public record TaskSubmitRequest(
        @Schema(description = "任务标题") @NotBlank(message = "任务标题不能为空") String title,
        @Schema(description = "任务入参（如 {items:[{name,amount}], claimedTotal: 100.00}）") @NotNull(message = "任务入参不能为空") Map<String, Object> inputParams
) {
}
