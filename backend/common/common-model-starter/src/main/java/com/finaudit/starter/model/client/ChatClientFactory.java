package com.finaudit.starter.model.client;

import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.metrics.ModelUsageSnapshot;

/**
 * 模型工厂：按类型提供模型客户端，统一 Token 统计与故障自动切换备用模型。
 * <p>实现 {@link DefaultChatClientFactory}：客户端由 Spring AI 实现（{@code OpenAiChatModel} 等，
 * 见 {@link DeepSeekAiClient}），调用经 {@code TrackingAiClient} 包装，主模型抛异常时按
 * {@code finaudit.model.fallback-type} 切换备用模型。</p>
 * <p>已知限制：当前仅注册 DeepSeek 一个实现，而 {@code fallback-type} 缺省为 null，
 * 故备用模型切换分支默认不会生效，待补齐 Qwen/Claude 实现并显式配置后启用。</p>
 */
public interface ChatClientFactory {

    /**
     * 获取指定类型的模型客户端。
     */
    AiClient getClient(ModelType type);

    /**
     * 进程内用量快照（P3.8 R9-1 接出口）。
     *
     * <p>默认返回 {@link ModelUsageSnapshot#empty()} 而不是 null：调用方（监控端点）无需处理空值，
     * 自定义实现若不统计也能安全接入。</p>
     */
    default ModelUsageSnapshot usageSnapshot() {
        return ModelUsageSnapshot.empty();
    }
}
