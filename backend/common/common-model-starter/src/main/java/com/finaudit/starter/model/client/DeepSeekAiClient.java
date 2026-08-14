package com.finaudit.starter.model.client;

import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.properties.ModelProperties;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

/**
 * DeepSeek 模型客户端（基于 Spring AI OpenAI 兼容端点）。
 * <p>默认端点 {@code https://api.deepseek.com}；密钥来自环境变量 {@code FINAUDIT_MODEL_API_KEY}，
 * 禁止在代码/配置中硬编码。</p>
 */
public class DeepSeekAiClient implements AiClient {

    /** DeepSeek 默认端点（OpenAI 兼容，无 /v1 前缀） */
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    /** DeepSeek 补全路径（与 baseUrl 拼接） */
    private static final String COMPLETIONS_PATH = "/chat/completions";
    /** 默认模型名 */
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final OpenAiChatModel chatModel;

    public DeepSeekAiClient(ModelProperties properties) {
        this.chatModel = buildChatModel(properties);
    }

    private OpenAiChatModel buildChatModel(ModelProperties props) {
        String apiKey = props.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少 DeepSeek API Key：请设置环境变量 FINAUDIT_MODEL_API_KEY");
        }
        String baseUrl = isBlank(props.getBaseUrl()) ? DEFAULT_BASE_URL : props.getBaseUrl();
        String modelName = isBlank(props.getModelName()) ? DEFAULT_MODEL : props.getModelName();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(COMPLETIONS_PATH)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .build())
                .build();
    }

    @Override
    public ModelType getModelType() {
        return ModelType.DEEPSEEK;
    }

    @Override
    public ChatReply chatWithUsage(String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)));
        ChatResponse response = chatModel.call(prompt);
        String text = response.getResult().getOutput().getText();

        var usage = response.getMetadata().getUsage();
        int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return new ChatReply(text, new TokenUsage(promptTokens, completionTokens));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
