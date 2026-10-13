package com.finaudit.starter.model.metrics;

import com.finaudit.starter.model.ModelType;

/**
 * 模型用量快照（P3.8 R9-1）：进程内累计的调用次数与 Token 用量。
 *
 * <p><b>与 {@code model_call_log} 台账的分工</b>：台账是**跨重启的持久事实**（成本指标的唯一权威来源）；
 * 本快照是**进程内即时观测**，用于「服务刚起来、台账还没写几行时」快速判断模型是否在正常被调用，
 * 以及监控端点的轻量读取（不必查库）。两者口径一致（同一处累加），但**统计区间不同**：
 * 快照 = 本进程启动至今，台账 = 表内全部行。</p>
 *
 * @param calls            成功调用次数
 * @param failed           失败调用次数
 * @param promptTokens     累计输入 tokens
 * @param completionTokens 累计输出 tokens
 * @param fallbackCalls    走了备用模型的次数
 * @param modelType        默认模型类型
 * @param modelName        模型名（如 deepseek-chat）
 */
public record ModelUsageSnapshot(long calls, long failed, long promptTokens, long completionTokens,
                                 long fallbackCalls, ModelType modelType, String modelName) {

    /** 无统计能力时的占位（实现方不支持快照时返回，避免调用方处理 null） */
    public static ModelUsageSnapshot empty() {
        return new ModelUsageSnapshot(0, 0, 0, 0, 0, null, null);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /** 失败率（百分比，保留两位；无调用时为 0） */
    public double failRatePct() {
        long total = calls + failed;
        return total == 0 ? 0d : Math.round(failed * 10000d / total) / 100d;
    }

    public String summary() {
        return "calls=" + calls + ", failed=" + failed
                + ", promptTokens=" + promptTokens
                + ", completionTokens=" + completionTokens
                + ", totalTokens=" + totalTokens()
                + ", fallbackCalls=" + fallbackCalls;
    }
}
