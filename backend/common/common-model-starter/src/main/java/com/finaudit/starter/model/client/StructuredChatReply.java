package com.finaudit.starter.model.client;

/**
 * 结构化对话回复：模型输出反序列化后的类型化数据 + 原始文本 + Token 用量。
 *
 * @param <T> 结构化输出目标类型
 * @param data 反序列化后的类型化结果（{@link AiClient#chatStructured} 的产出）
 * @param text 模型原始回复文本（供排查/留痕）
 * @param usage Token 用量
 */
public record StructuredChatReply<T>(T data, String text, TokenUsage usage) {
}
