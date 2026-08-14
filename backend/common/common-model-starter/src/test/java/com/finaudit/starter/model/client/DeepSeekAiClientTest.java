package com.finaudit.starter.model.client;

import com.finaudit.starter.model.properties.ModelProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DeepSeek 客户端测试。
 * <p>真实调用需要环境变量 FINAUDIT_MODEL_API_KEY，未设置时自动跳过（不联网时 CI 不失败）。</p>
 */
class DeepSeekAiClientTest {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiClientTest.class);

    @Test
    void chat_returnsReplyFromDeepSeek() {
        String apiKey = System.getenv("FINAUDIT_MODEL_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未设置 FINAUDIT_MODEL_API_KEY，跳过真实调用验证");

        ModelProperties props = new ModelProperties();
        props.setApiKey(apiKey);
        props.setBaseUrl(System.getenv("FINAUDIT_MODEL_BASE_URL"));
        props.setModelName(System.getenv("FINAUDIT_MODEL_NAME"));

        DeepSeekAiClient client = new DeepSeekAiClient(props);
        ChatReply reply = client.chatWithUsage(
                "你是一个财务审核助手，只回复与金额核验相关的内容。",
                "请回复两个字：收到。");

        log.info("=== DeepSeek 回复: {} ===", reply.text());
        log.info("=== Token 用量: {} ===", reply.usage());
        assertNotNull(reply.text());
        assertFalse(reply.text().isBlank());
    }

    @Test
    void missingApiKey_throws() {
        ModelProperties props = new ModelProperties();
        props.setApiKey("");
        assertThrows(IllegalStateException.class, () -> new DeepSeekAiClient(props));
    }

    @Test
    void factory_recordsUsageAndFailover() {
        ModelProperties props = new ModelProperties();
        props.setApiKey("sk-xxx");
        DeepSeekAiClient raw = new DeepSeekAiClient(props);

        java.util.Map<com.finaudit.starter.model.ModelType, AiClient> clients = new java.util.EnumMap<>(
                com.finaudit.starter.model.ModelType.class);
        clients.put(com.finaudit.starter.model.ModelType.DEEPSEEK, raw);
        DefaultChatClientFactory factory = new DefaultChatClientFactory(
                clients, com.finaudit.starter.model.ModelType.DEEPSEEK, null);

        AiClient tracked = factory.getClient(com.finaudit.starter.model.ModelType.DEEPSEEK);
        assertNotNull(tracked);
        assertNotNull(factory.usageSnapshot());
        assertFalse(factory.usageSnapshot().summary().isBlank());
    }
}
