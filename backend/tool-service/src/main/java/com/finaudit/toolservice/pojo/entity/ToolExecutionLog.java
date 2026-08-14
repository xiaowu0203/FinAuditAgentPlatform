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
     */
    public static ToolExecutionLog from(ToolExecuteMessage msg, Map<String, Object> result,
                                        ToolExecStatus status, long cost) {
        ToolExecutionLog execLog = new ToolExecutionLog();
        execLog.setTenantId(msg.tenantId());
        execLog.setTaskId(msg.taskId());
        execLog.setStepId(msg.stepId());
        execLog.setToolCode(msg.toolCode());
        execLog.setInputParams(msg.inputParams());
        execLog.setResult(result);
        execLog.setCostTimeMs(cost);
        execLog.setStatus(status.name());
        return execLog;
    }
}
