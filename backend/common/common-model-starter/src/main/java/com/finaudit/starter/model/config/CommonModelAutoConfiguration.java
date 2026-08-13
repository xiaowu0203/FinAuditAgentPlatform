package com.finaudit.starter.model.config;

import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.model.client.DeepSeekAiClient;
import com.finaudit.starter.model.client.DefaultChatClientFactory;
import com.finaudit.starter.model.properties.ModelProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型通用配置自动装配：注册 DeepSeek 客户端 + 统一模型工厂。
 * <p>仅当 classpath 存在 spring-ai-openai 的 {@link OpenAiChatModel} 时生效。</p>
 */
@AutoConfiguration
@ConditionalOnClass(OpenAiChatModel.class)
@EnableConfigurationProperties(ModelProperties.class)
public class CommonModelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeepSeekAiClient deepSeekAiClient(ModelProperties properties) {
        return new DeepSeekAiClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ChatClientFactory.class)
    public ChatClientFactory chatClientFactory(ObjectProvider<AiClient> aiClients, ModelProperties properties) {
        Map<ModelType, AiClient> clients = new HashMap<>();
        aiClients.orderedStream().forEach(c -> clients.putIfAbsent(c.getModelType(), c));
        return new DefaultChatClientFactory(clients, properties.getType(), properties.getFallbackType());
    }
}
