package com.finaudit.agentcore.pojo.dto;

import com.finaudit.agentcore.enums.TaskType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 任务提交请求。
 *
 * @param title       任务标题
 * @param inputParams 任务入参（如报销审核场景为 {items:[{name,amount}], claimedTotal: 100.00}）
 * @param taskType    业务类型：REIMBURSEMENT 报销审核 / GENERIC 通用分析；缺省 GENERIC（P1 手工任务入口不传）
 */
public record TaskSubmitRequest(
        @Schema(description = "任务标题") @NotBlank(message = "任务标题不能为空") String title,
        @Schema(description = "任务入参（如 {items:[{name,amount}], claimedTotal: 100.00}）") @NotNull(message = "任务入参不能为空") Map<String, Object> inputParams,
        @Schema(description = "业务类型：REIMBURSEMENT 报销审核 / GENERIC 通用分析（缺省 GENERIC）") TaskType taskType
) {
    /** 便捷构造：未显式指定业务类型按通用任务处理（兼容 P1 手工任务入口）。 */
    public TaskSubmitRequest(String title, Map<String, Object> inputParams) {
        this(title, inputParams, TaskType.GENERIC);
    }
}
