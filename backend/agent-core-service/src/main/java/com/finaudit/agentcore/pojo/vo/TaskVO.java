package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.AgentTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务视图对象。
 */
@Data
public class TaskVO {

    @Schema(description = "任务ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "业务类型（REIMBURSEMENT 报销审核 / GENERIC 通用分析）")
    private String taskType;

    @Schema(description = "任务入参（JSON）")
    private Map<String, Object> inputParams;

    @Schema(description = "任务状态（PENDING / RUNNING / SUCCESS / FAILED）")
    private String status;

    @Schema(description = "总步骤数")
    private Integer totalSteps;

    @Schema(description = "已完成步骤数")
    private Integer finishedSteps;

    @Schema(description = "任务结果（JSON）")
    private Map<String, Object> result;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static TaskVO from(AgentTask task) {
        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setTenantId(task.getTenantId());
        vo.setTaskNo(task.getTaskNo());
        vo.setTitle(task.getTitle());
        vo.setTaskType(task.getTaskType());
        vo.setInputParams(task.getInputParams());
        vo.setStatus(task.getStatus());
        vo.setTotalSteps(task.getTotalSteps());
        vo.setFinishedSteps(task.getFinishedSteps());
        vo.setResult(task.getResult());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setCreatedAt(task.getCreatedAt());
        return vo;
    }
}
