package com.finaudit.starter.model.properties;

import com.finaudit.starter.model.ModelType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型客户端通用配置。
 * <p>密钥禁止入库，一律通过环境变量注入（见 .env.example 的 FINAUDIT_MODEL_API_KEY）。</p>
 */
@ConfigurationProperties(prefix = "finaudit.model")
@Getter
@Setter
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
    /** 备用模型类型（默认模型故障时切换；可为 null，P1 未实现 Qwen/Claude 时保持空） */
    private ModelType fallbackType;

}
