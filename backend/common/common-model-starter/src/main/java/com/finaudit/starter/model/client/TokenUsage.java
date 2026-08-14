package com.finaudit.starter.model.client;

/**
 * 单次模型调用的 Token 用量统计。
 *
 * @param promptTokens     输入 tokens
 * @param completionTokens 输出 tokens
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    /** 零用量（调用失败时用于占位） */
    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
