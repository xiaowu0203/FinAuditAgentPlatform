package com.finaudit.starter.model.client;

import com.finaudit.starter.model.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认模型工厂：按类型管理客户端、统一 Token 统计、DeepSeek 故障时切换备用模型。
 * <p>统计口径：每次对用户的调用记一次成功/失败；token 仅成功时累计。</p>
 */
public class DefaultChatClientFactory implements ChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatClientFactory.class);

    private final Map<ModelType, AiClient> clients;
    private final ModelType defaultType;
    private final ModelType fallbackType;

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong totalPromptTokens = new AtomicLong();
    private final AtomicLong totalCompletionTokens = new AtomicLong();

    private final Map<ModelType, AiClient> trackedClients = new ConcurrentHashMap<>();

    public DefaultChatClientFactory(Map<ModelType, AiClient> clients,
                                    ModelType defaultType, ModelType fallbackType) {
        this.clients = new EnumMap<>(clients);
        this.defaultType = defaultType;
        this.fallbackType = fallbackType;
    }

    @Override
    public AiClient getClient(ModelType type) {
        return trackedClients.computeIfAbsent(type, t -> new TrackingAiClient(t, requireClient(t)));
    }

    /** 统计快照（P1 落日志+计数，P2 落库） */
    public UsageSnapshot usageSnapshot() {
        return new UsageSnapshot(totalCalls.get(), failedCalls.get(),
                totalPromptTokens.get(), totalCompletionTokens.get());
    }

    private AiClient requireClient(ModelType type) {
        AiClient client = clients.get(type);
        if (client == null) {
            throw new IllegalStateException("未注册模型类型: " + type);
        }
        return client;
    }

    /**
     * 带统计 + 故障切换的客户端包装。
     */
    private final class TrackingAiClient implements AiClient {

        private final ModelType type;
        private final AiClient primary;

        private TrackingAiClient(ModelType type, AiClient primary) {
            this.type = type;
            this.primary = primary;
        }

        @Override
        public ModelType getModelType() {
            return type;
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt) {
            try {
                ChatReply reply = primary.chatWithUsage(systemPrompt, userPrompt);
                record(reply.usage(), true);
                return reply;
            } catch (Exception primaryEx) {
                AiClient fallback = resolveFallback();
                if (fallback != null) {
                    log.warn("[model] {} 调用失败，切换备用模型 {}", type, fallbackType, primaryEx);
                    try {
                        ChatReply reply = fallback.chatWithUsage(systemPrompt, userPrompt);
                        record(reply.usage(), true);
                        return reply;
                    } catch (Exception fallbackEx) {
                        log.error("[model] 备用模型 {} 也调用失败", fallbackType, fallbackEx);
                        record(TokenUsage.ZERO, false);
                        throw primaryEx;
                    }
                }
                log.error("[model] {} 调用失败: {}", type, primaryEx.getMessage(), primaryEx);
                record(TokenUsage.ZERO, false);
                throw primaryEx;
            }
        }

        private AiClient resolveFallback() {
            if (fallbackType == null || fallbackType == type) {
                return null;
            }
            return clients.get(fallbackType);
        }
    }

    private void record(TokenUsage usage, boolean success) {
        if (success) {
            totalCalls.incrementAndGet();
            totalPromptTokens.addAndGet(usage.promptTokens());
            totalCompletionTokens.addAndGet(usage.completionTokens());
        } else {
            failedCalls.incrementAndGet();
        }
    }

    /** 统计快照 */
    public record UsageSnapshot(long calls, long failed, long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }

        public String summary() {
            return "calls=" + calls + ", failed=" + failed
                    + ", promptTokens=" + promptTokens
                    + ", completionTokens=" + completionTokens
                    + ", totalTokens=" + totalTokens();
        }
    }
}
