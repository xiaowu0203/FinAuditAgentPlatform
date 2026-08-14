package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.TaskStatus;
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

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "任务入参（JSON）")
    private Map<String, Object> inputParams;

    @Schema(description = "任务状态（PENDING / RUNNING / SUCCESS / FAILED）")
    private String status;

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
     */
    public static AgentTask from(TaskSubmitRequest request, Long tenantId) {
        AgentTask task = new AgentTask();
        task.setTenantId(tenantId);
        task.setTaskNo(generateTaskNo());
        task.setTitle(request.title());
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
}
