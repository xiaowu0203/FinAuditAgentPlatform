package com.finaudit.starter.model.client;

import com.finaudit.starter.model.ModelType;

/**
 * 模型工厂：统一管理密钥、Token 统计、故障自动切换备用模型。
 * <p>TODO(P1): 接入 Spring AI，实现各模型实现 + 调用统计 + 切换策略。</p>
 */
public interface ChatClientFactory {

    /**
     * 获取指定类型的模型客户端。
     */
    AiClient getClient(ModelType type);
}
