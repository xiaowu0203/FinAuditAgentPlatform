package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.StepStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 任务步骤（agent_task_step）。
 * <p>断点续跑载体；状态机见 {@code StepStatus}。</p>
 */
@Getter
@Setter
@TableName(value = "agent_task_step", autoResultMap = true)
public class AgentTaskStep {

    @TableId(type = IdType.AUTO)
    @Schema(description = "步骤ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "步骤序号")
    private Integer stepNo;

    @Schema(description = "步骤名称")
    private String stepName;

    @Schema(description = "步骤类型")
    private String stepType;

    @Schema(description = "工具名称")
    private String toolName;

    @Schema(description = "执行角色（AgentRole 枚举名，可空；历史与 GENERIC 步骤为空）")
    private String agentRole;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "步骤入参（JSON）")
    private Map<String, Object> inputParams;

    /**
     * `output` 是 jsqlparser 5.1 保留字，多租户拦截器解析 SQL 时会失败；
     * 用反引号引用后按普通标识符处理（MySQL 原生接受反引号，无需改表结构）
     */
    @TableField(value = "`output`", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "步骤输出（JSON）")
    private Map<String, Object> output;

    @Schema(description = "步骤状态（PENDING / RUNNING / SUCCESS / FAILED）")
    private String status;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由规划步骤构造待执行步骤（初始状态 PENDING，重试 0 次）。
     */
    public static AgentTaskStep from(TaskPlanStep plan, Long tenantId, Long taskId, int stepNo) {
        AgentTaskStep step = new AgentTaskStep();
        step.setTenantId(tenantId);
        step.setTaskId(taskId);
        step.setStepNo(stepNo);
        step.setStepName(plan.stepName());
        step.setStepType(plan.stepType());
        step.setToolName(plan.toolName());
        step.setAgentRole(plan.agentRole());
        step.setInputParams(plan.inputParams());
        step.setStatus(StepStatus.PENDING.name());
        step.setRetryCount(0);
        return step;
    }
}
