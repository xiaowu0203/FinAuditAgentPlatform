package com.finaudit.starter.model.client;

import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.metrics.ModelCallContext;
import com.finaudit.starter.model.metrics.ModelCallRecord;
import com.finaudit.starter.model.metrics.ModelCallRecorder;
import com.finaudit.starter.model.metrics.ModelUsageSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认模型工厂：按类型管理客户端、统一 Token 统计与台账、DeepSeek 故障时切换备用模型。
 * <p>统计口径：每次对用户的调用记一次成功/失败；token 仅成功时累计。</p>
 * <p><b>P3.8 R9-1 起「统计」有了出口</b>：内存累加仍在（{@link #usageSnapshot()}，进程内即时观测），
 * 同时每次调用经 {@link ModelCallRecorder} 落一条台账（可持久化，跨重启保留），
 * 并打一行结构化日志（key=value，便于日志系统按字段抽取）。此前仅有内存计数且无任何消费方，
 * 等于成本指标不存在。</p>
 */
public class DefaultChatClientFactory implements ChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatClientFactory.class);

    /** 失败原因截断长度（与落库列长度对齐，避免超长写库失败） */
    private static final int MAX_ERROR_LEN = 480;

    private final Map<ModelType, AiClient> clients;
    private final ModelType defaultType;
    private final ModelType fallbackType;
    private final String modelName;
    /** 台账记录器（可空：未实现 SPI 的服务不记账） */
    private final ModelCallRecorder recorder;

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong totalPromptTokens = new AtomicLong();
    private final AtomicLong totalCompletionTokens = new AtomicLong();
    /** 故障切换到备用模型的次数（成本/稳定性观测：切换成功也算成功，但值得单独看） */
    private final AtomicLong fallbackCalls = new AtomicLong();

    private final Map<ModelType, AiClient> trackedClients = new ConcurrentHashMap<>();

    public DefaultChatClientFactory(Map<ModelType, AiClient> clients,
                                   ModelType defaultType, ModelType fallbackType) {
        this(clients, defaultType, fallbackType, null, null);
    }

    public DefaultChatClientFactory(Map<ModelType, AiClient> clients,
                                   ModelType defaultType, ModelType fallbackType,
                                   String modelName, ModelCallRecorder recorder) {
        this.clients = new EnumMap<>(clients);
        this.defaultType = defaultType;
        this.fallbackType = fallbackType;
        this.modelName = modelName;
        this.recorder = recorder;
    }

    @Override
    public AiClient getClient(ModelType type) {
        return trackedClients.computeIfAbsent(type, t -> new TrackingAiClient(t, requireClient(t)));
    }

    /** 统计快照（进程内即时观测；跨重启的累计请看 model_call_log 台账） */
    @Override
    public ModelUsageSnapshot usageSnapshot() {
        return new ModelUsageSnapshot(totalCalls.get(), failedCalls.get(),
                totalPromptTokens.get(), totalCompletionTokens.get(), fallbackCalls.get(),
                defaultType, modelName);
    }

    /** 默认模型类型（供健康检查/指标端点展示） */
    public ModelType defaultType() {
        return defaultType;
    }

    private AiClient requireClient(ModelType type) {
        AiClient client = clients.get(type);
        if (client == null) {
            throw new IllegalStateException("未注册模型类型: " + type);
        }
        return client;
    }

    /**
     * 带统计 + 台账 + 故障切换的客户端包装。
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
            long start = System.currentTimeMillis();
            try {
                ChatReply reply = primary.chatWithUsage(systemPrompt, userPrompt);
                finish(type, reply.usage(), System.currentTimeMillis() - start, true, false, null);
                return reply;
            } catch (Exception primaryEx) {
                AiClient fallback = resolveFallback();
                if (fallback != null) {
                    log.warn("[model] {} 调用失败，切换备用模型 {}", type, fallbackType, primaryEx);
                    try {
                        ChatReply reply = fallback.chatWithUsage(systemPrompt, userPrompt);
                        finish(fallbackType, reply.usage(), System.currentTimeMillis() - start, true, true, null);
                        return reply;
                    } catch (Exception fallbackEx) {
                        log.error("[model] 备用模型 {} 也调用失败", fallbackType, fallbackEx);
                        finish(type, TokenUsage.ZERO, System.currentTimeMillis() - start, false, true,
                                fallbackEx.getMessage());
                        throw primaryEx;
                    }
                }
                log.error("[model] {} 调用失败: {}", type, primaryEx.getMessage(), primaryEx);
                finish(type, TokenUsage.ZERO, System.currentTimeMillis() - start, false, false, primaryEx.getMessage());
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

    /**
     * 调用收尾：内存计数 + 结构化日志 + 台账落库（经 SPI）。
     *
     * @param effectiveType 实际生效的模型类型（故障切换后为备用类型）
     */
    private void finish(ModelType effectiveType, TokenUsage usage, long latencyMs,
                        boolean success, boolean fallbackUsed, String errorMsg) {
        if (success) {
            totalCalls.incrementAndGet();
            totalPromptTokens.addAndGet(usage.promptTokens());
            totalCompletionTokens.addAndGet(usage.completionTokens());
        } else {
            failedCalls.incrementAndGet();
        }
        if (fallbackUsed) {
            fallbackCalls.incrementAndGet();
        }

        ModelCallContext ctx = ModelCallContext.current();
        String scene = ctx == null ? null : ctx.scene();
        Long tenantId = ctx == null ? null : ctx.tenantId();
        Long taskId = ctx == null ? null : ctx.taskId();
        Long stepId = ctx == null ? null : ctx.stepId();

        // 结构化日志：字段化输出，便于日志系统按 model/scene/taskId 聚合
        log.info("[model-call] modelType={} modelName={} scene={} tenantId={} taskId={} stepId={} "
                        + "promptTokens={} completionTokens={} totalTokens={} latencyMs={} success={} fallbackUsed={}",
                effectiveType, modelName, scene, tenantId, taskId, stepId,
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                latencyMs, success, fallbackUsed);

        if (recorder == null) {
            return;
        }
        try {
            recorder.record(new ModelCallRecord(effectiveType, modelName, scene, tenantId, taskId, stepId,
                    usage.promptTokens(), usage.completionTokens(), latencyMs, success, fallbackUsed,
                    truncate(errorMsg)));
        } catch (Exception e) {
            // 台账是旁路：绝不允许记账失败影响模型调用结果
            log.warn("[model] 调用台账记录失败（不影响调用结果）: {}", e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
