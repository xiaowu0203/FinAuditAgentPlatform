package com.finaudit.starter.model.client;

/**
 * 统一 AI 客户端抽象。
 * <p>TODO(P1): 基于 Spring AI 实现 DeepSeek / Qwen / Claude 客户端。</p>
 */
public interface AiClient {

    /**
     * 单轮对话。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入
     * @return 模型回复文本
     */
    String chat(String systemPrompt, String userPrompt);
}
