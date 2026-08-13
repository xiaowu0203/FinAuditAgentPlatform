package com.finaudit.starter.model.properties;

import com.finaudit.starter.model.ModelType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型客户端通用配置。
 * <p>密钥禁止入库，一律通过环境变量注入（见 .env.example 的 FINAUDIT_MODEL_API_KEY）。</p>
 */
@ConfigurationProperties(prefix = "finaudit.model")
public class ModelProperties {

    /** 默认模型类型 */
    private ModelType type = ModelType.DEEPSEEK;
    /** 模型名，如 deepseek-chat */
    private String modelName;
    /** API Key（环境变量注入） */
    private String apiKey;
    /** Base URL，为空则用各模型默认地址 */
    private String baseUrl;
    /** 请求超时（秒） */
    private int timeoutSeconds = 60;

    public ModelType getType() {
        return type;
    }

    public void setType(ModelType type) {
        this.type = type;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
