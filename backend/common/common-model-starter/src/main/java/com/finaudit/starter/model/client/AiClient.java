package com.finaudit.starter.model.client;

import com.finaudit.starter.model.ModelType;

/**
 * 统一 AI 客户端抽象。
 * <p>各模型实现基于 Spring AI {@code ChatModel}；调用统一走 {@link #chatWithUsage(String, String)}
 * 以便记录 Token 用量。</p>
 */
public interface AiClient {

    /** 该客户端对应的模型类型 */
    ModelType getModelType();

    /**
     * 便捷方法：仅返回模型回复文本。
     */
    default String chat(String systemPrompt, String userPrompt) {
        return chatWithUsage(systemPrompt, userPrompt).text();
    }

    /**
     * 单轮对话，返回回复文本与 Token 用量。
     */
    ChatReply chatWithUsage(String systemPrompt, String userPrompt);
}
