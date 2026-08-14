package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 任务步骤视图对象。
 */
@Data
public class StepVO {

    @Schema(description = "步骤ID")
    private Long id;

    @Schema(description = "步骤序号")
    private Integer stepNo;

    @Schema(description = "步骤名称")
    private String stepName;

    @Schema(description = "步骤类型")
    private String stepType;

    @Schema(description = "工具名称")
    private String toolName;

    @Schema(description = "步骤入参（JSON）")
    private Map<String, Object> inputParams;

    @Schema(description = "步骤输出（JSON）")
    private Map<String, Object> output;

    @Schema(description = "步骤状态（PENDING / RUNNING / SUCCESS / FAILED）")
    private String status;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Schema(description = "重试次数")
    private Integer retryCount;

    public static StepVO from(AgentTaskStep step) {
        StepVO vo = new StepVO();
        vo.setId(step.getId());
        vo.setStepNo(step.getStepNo());
        vo.setStepName(step.getStepName());
        vo.setStepType(step.getStepType());
        vo.setToolName(step.getToolName());
        vo.setInputParams(step.getInputParams());
        vo.setOutput(step.getOutput());
        vo.setStatus(step.getStatus());
        vo.setErrorMsg(step.getErrorMsg());
        vo.setRetryCount(step.getRetryCount());
        return vo;
    }
}
