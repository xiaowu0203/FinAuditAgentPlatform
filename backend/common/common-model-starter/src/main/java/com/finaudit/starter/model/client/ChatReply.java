package com.finaudit.starter.model.client;

/**
 * 模型回复（文本 + Token 用量）。
 *
 * @param text  模型回复文本
 * @param usage 本次调用 Token 用量
 */
public record ChatReply(String text, TokenUsage usage) {
}
