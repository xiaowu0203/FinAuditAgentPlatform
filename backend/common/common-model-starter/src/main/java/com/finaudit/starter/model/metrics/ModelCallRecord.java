package com.finaudit.starter.model.metrics;

import com.finaudit.starter.model.ModelType;

/**
 * 单次模型调用记录（P3.8 R9-1）。
 *
 * <p><b>它解决什么问题</b>：此前 Token 用量只在 {@code DefaultChatClientFactory} 里做内存累加
 * （{@code usageSnapshot()} 除测试外无人调用），进程一重启全部归零——等于「成本指标不存在」。
 * 本记录是**可落库的调用台账**：一次调用一行，带任务/步骤关联，成为成本与效率指标的数据源。</p>
 *
 * @param modelType        模型类型（DEEPSEEK 等）
 * @param modelName        模型名（如 deepseek-chat；取自配置）
 * @param scene            调用场景（如 llm_step / task_plan / self_check；便于按用途拆分成本）
 * @param tenantId         租户 ID（可空：无上下文直调时为空）
 * @param taskId           任务 ID（可空：任务规划发生在任务创建后，故一般有值）
 * @param stepId           步骤 ID（可空：非步骤级调用，如规划器调用）
 * @param promptTokens     输入 tokens（失败时为 0）
 * @param completionTokens 输出 tokens（失败时为 0）
 * @param latencyMs        本次调用耗时（毫秒，含故障切换后的总耗时）
 * @param success          是否成功（故障切换成功也算成功）
 * @param fallbackUsed     是否走了备用模型
 * @param errorMsg         失败原因（成功时为 null；截断保存，避免超长）
 */
public record ModelCallRecord(
        ModelType modelType,
        String modelName,
        String scene,
        Long tenantId,
        Long taskId,
        Long stepId,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        boolean success,
        boolean fallbackUsed,
        String errorMsg) {

    /** 总 tokens（落库时冗余存一列，便于直接聚合，无需每次写表达式） */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
