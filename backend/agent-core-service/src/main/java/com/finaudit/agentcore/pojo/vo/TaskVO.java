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

    @Schema(description = "自校验纠错次数（P3.8 R5：命中矛盾重跑风控语义步骤的次数）")
    private Integer correctionCount;

    @Schema(description = "语义自校验结果（是否通过 + 矛盾/幻觉清单 + 断言条数）")
    private Map<String, Object> selfCheckResult;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "任务耗时（毫秒，本次执行；P3.8 R9-2）")
    private Long durationMs;

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
        // P3.8 R5：自校验明细透出，前端任务详情可展示「纠错 N 次 + 自校验明细」
        vo.setCorrectionCount(task.getCorrectionCount());
        vo.setSelfCheckResult(task.getSelfCheckResult());
        vo.setCreatedAt(task.getCreatedAt());
        // P3.8 R9-2：耗时透出，前端任务详情可展示「本次执行 X 秒」
        vo.setDurationMs(task.getDurationMs());
        return vo;
    }
}
