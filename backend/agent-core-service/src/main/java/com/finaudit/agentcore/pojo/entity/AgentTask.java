package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.pojo.dto.TaskSubmitRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Agent 任务（agent_task）。
 * <p>状态机见 {@code TaskStatus}；JSON 列用 {@link JacksonTypeHandler}。</p>
 */
@Getter
@Setter
@TableName(value = "agent_task", autoResultMap = true)
public class AgentTask {

    @TableId(type = IdType.AUTO)
    @Schema(description = "任务ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "业务类型（REIMBURSEMENT 报销审核 / GENERIC 通用分析；P3 角色化分派依据）")
    private String taskType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "任务入参（JSON）")
    private Map<String, Object> inputParams;

    @Schema(description = "任务状态（PENDING / RUNNING / SUCCESS / FAILED）")
    private String status;

    @Schema(description = "本次执行开始时间（启动/修改重跑时刷新；任务级超时预算的计时起点）")
    private LocalDateTime startedAt;

    @Schema(description = "总步骤数")
    private Integer totalSteps;

    @Schema(description = "已完成步骤数")
    private Integer finishedSteps;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "任务结果（JSON）")
    private Map<String, Object> result;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Schema(description = "创建人ID")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /** 任务号时间格式 */
    private static final DateTimeFormatter TASK_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 由提交请求构造新任务（初始状态 PENDING，步骤数 0，自动生成任务号）。
     *
     * @param createdBy 创建人用户 ID（网关注入的 X-User-Id；报销单提交为申请人 applicantId）
     */
    public static AgentTask from(TaskSubmitRequest request, Long tenantId, Long createdBy) {
        AgentTask task = new AgentTask();
        task.setTenantId(tenantId);
        task.setCreatedBy(createdBy);
        task.setTaskNo(generateTaskNo());
        task.setTitle(request.title());
        task.setTaskType(request.taskType() == null ? TaskType.GENERIC.name() : request.taskType().name());
        task.setInputParams(request.inputParams());
        task.setStatus(TaskStatus.PENDING.name());
        task.setTotalSteps(0);
        task.setFinishedSteps(0);
        return task;
    }

    /** 任务号：T + yyyyMMddHHmmss + 4 位随机数 */
    private static String generateTaskNo() {
        return "T" + LocalDateTime.now().format(TASK_NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /**
     * 作废回写（提交人撤回 / 财务同意撤销）：置 CANCELLED 并记录原因，供前端展示与 resume 拒绝。
     */
    public void applyCancelled(String reason) {
        this.status = TaskStatus.CANCELLED.name();
        this.errorMsg = reason;
    }
}
