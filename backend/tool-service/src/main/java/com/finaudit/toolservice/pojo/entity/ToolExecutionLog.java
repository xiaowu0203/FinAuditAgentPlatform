package com.finaudit.toolservice.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.starter.mq.message.ToolExecuteMessage;
import com.finaudit.toolservice.enums.ToolExecStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工具执行日志（tool_execution_log）。
 */
@Getter
@Setter
@TableName(value = "tool_execution_log", autoResultMap = true)
public class ToolExecutionLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "执行日志ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "步骤ID")
    private Long stepId;

    @Schema(description = "工具编码")
    private String toolCode;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "工具入参（JSON）")
    private Map<String, Object> inputParams;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "执行结果（JSON）")
    private Map<String, Object> result;

    @Schema(description = "执行耗时（毫秒）")
    private Long costTimeMs;

    @Schema(description = "执行状态（SUCCESS / FAILED）")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 由工具执行消息构造执行日志。
     * <p>{@code input_params} 在 DDL 上是 {@code JSON NOT NULL}，而 MyBatis-Plus 默认 NOT_NULL
     * 字段策略会跳过 null 列，入参为 null 时 INSERT 直接撞非空约束。此处统一归一化为空 Map，
     * 保证留痕可落库（消费侧 {@link com.finaudit.toolservice.service.ToolExecutionService}
     * 另有 try/catch 兜底，双层防御：本处保证正常落库，那处保证失败不阻断结果回吐）。</p>
     */
    public static ToolExecutionLog from(ToolExecuteMessage msg, Map<String, Object> result,
                                        ToolExecStatus status, long cost) {
        ToolExecutionLog execLog = new ToolExecutionLog();
        execLog.setTenantId(msg.tenantId());
        execLog.setTaskId(msg.taskId());
        execLog.setStepId(msg.stepId());
        execLog.setToolCode(msg.toolCode());
        // input_params 非空约束：null 归一化为空 Map，避免 NOT_NULL 策略跳过该列导致插入失败
        execLog.setInputParams(msg.inputParams() == null ? Map.of() : msg.inputParams());
        execLog.setResult(result);
        execLog.setCostTimeMs(cost);
        execLog.setStatus(status.name());
        return execLog;
    }
}
