package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finaudit.starter.model.metrics.ModelCallRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 模型调用台账（model_call_log，P3.8 R9-1）。
 *
 * <p><b>为什么单独建表而不是给 agent_task_step 加列</b>：一次步骤可能调用多次模型
 * （结构化输出解析失败会重试第二次、故障会切备用模型），加列只能存"最后一条"，
 * 成本与失败率都统计不准。台账是"一次调用一行"的事实表。</p>
 *
 * <p>租户隔离：表带 {@code tenant_id}，由 {@code TenantLineInnerInterceptor} 自动过滤；
 * 写入时若线程无租户上下文（极少数直调）则以记录里的 tenantId 为准（见 {@code ModelCallLogService}）。</p>
 */
@Getter
@Setter
@TableName("model_call_log")
public class ModelCallLog {

    /**
     * {@code error_msg} 列的字符上限（与 DDL 的 {@code VARCHAR(500)} 对齐）。
     * <p>MySQL 的 VARCHAR(n) 按**字符**计数（不是字节），故按字符截断即可。</p>
     */
    public static final int ERROR_MSG_MAX_LEN = 500;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "模型类型（DEEPSEEK 等）")
    private String modelType;

    @Schema(description = "模型名（如 deepseek-chat）")
    private String modelName;

    @Schema(description = "调用场景（llm_step / task_plan 等）")
    private String scene;

    @Schema(description = "任务ID（可空）")
    private Long taskId;

    @Schema(description = "步骤ID（可空）")
    private Long stepId;

    @Schema(description = "输入 tokens")
    private Integer promptTokens;

    @Schema(description = "输出 tokens")
    private Integer completionTokens;

    @Schema(description = "总 tokens（冗余列，便于直接聚合）")
    private Integer totalTokens;

    @Schema(description = "调用耗时（毫秒，含故障切换）")
    private Long latencyMs;

    @Schema(description = "是否成功（故障切换成功仍计成功）")
    private Integer success;

    @Schema(description = "是否走了备用模型")
    private Integer fallbackUsed;

    @Schema(description = "失败原因（截断保存）")
    private String errorMsg;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 由 SPI 记录构造台账实体（转换封装在实体类，业务层不手写 set 组装）。
     * <p>直接消费 Starter 的 {@link ModelCallRecord}：不为同一份数据再造一个内部 DTO，
     * 否则两处字段会各自漂移。</p>
     *
     * <p><b>⚠️ 为什么在这里再截断一次 errorMsg</b>：Starter 侧已有 480 字符截断，但那只保护
     * 「经模型工厂回调」这一条路径；本类才是**知道列长的地方**（`VARCHAR(500)`）。
     * 真实库复现（`docs/test/repro/ModelCallLogInsertRepro.java`）证明：直接构造一条 900 字符
     * errorMsg 的记录会抛 {@code Data too long for column 'error_msg'}，而调用方的兜底 catch
     * 会让这行台账**被静默丢弃**——失败调用的台账恰恰是最需要留痕的。故在实体边界兜住。</p>
     */
    public static ModelCallLog from(ModelCallRecord data) {
        ModelCallLog log = new ModelCallLog();
        log.setTenantId(data.tenantId());
        log.setModelType(data.modelType() == null ? null : data.modelType().name());
        log.setModelName(data.modelName());
        log.setScene(data.scene());
        log.setTaskId(data.taskId());
        log.setStepId(data.stepId());
        log.setPromptTokens(data.promptTokens());
        log.setCompletionTokens(data.completionTokens());
        log.setTotalTokens(data.promptTokens() + data.completionTokens());
        log.setLatencyMs(data.latencyMs());
        log.setSuccess(data.success() ? 1 : 0);
        log.setFallbackUsed(data.fallbackUsed() ? 1 : 0);
        log.setErrorMsg(truncateError(data.errorMsg()));
        return log;
    }

    /** 按列长截断失败原因（null 原样返回） */
    private static String truncateError(String errorMsg) {
        if (errorMsg == null || errorMsg.length() <= ERROR_MSG_MAX_LEN) {
            return errorMsg;
        }
        return errorMsg.substring(0, ERROR_MSG_MAX_LEN);
    }
}
