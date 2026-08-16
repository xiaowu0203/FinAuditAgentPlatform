package com.finaudit.starter.model.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.starter.model.ModelType;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.ResponseTextCleaner;
import org.springframework.core.ParameterizedTypeReference;

/**
 * 统一 AI 客户端抽象。
 * <p>各模型实现基于 Spring AI {@code ChatModel}；调用统一走 {@link #chatWithUsage(String, String)}
 * 以便记录 Token 用量。</p>
 * <p>结构化输出 {@link #chatStructured} 走 Spring AI {@code BeanOutputConverter}：从目标类型生成
 * JSON Schema 注入提示词约束模型输出形状，再反序列化回该类型（模型无关，不依赖 provider 的
 * {@code response_format} 支持）。本方法为 default 实现、内部委托 {@link #chatWithUsage}，
 * 因此经 {@code DefaultChatClientFactory.TrackingAiClient} 的用量统计与故障降级自动生效。</p>
 */
public interface AiClient {

    /** 剥离模型回复中的 ```json 代码块包裹（镜像旧 TaskPlanner.parse 的防御处理） */
    ResponseTextCleaner JSON_CLEANER = text -> {
        String s = text == null ? "" : text.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last);
            }
        }
        return s.trim();
    };

    /** 该客户端对应的模型类型 */
    ModelType getModelType();

    /**
     * 便捷方法：仅返回模型回复文本。
     */
    default String chat(String systemPrompt, String userPrompt) {
        return chatWithUsage(systemPrompt, userPrompt).text();
    }

    /**
     * 单轮对话，返回回复文本与 Token 用量。
     */
    ChatReply chatWithUsage(String systemPrompt, String userPrompt);

    /**
     * 结构化输出（按目标类型解析）：系统提示词后追加由目标类型生成的 JSON Schema，
     * 模型回复反序列化为 {@code outputType} 对应实例。
     *
     * @param systemPrompt 系统提示词（行为约束；形状由自动生成的 Schema 接管）
     * @param userPrompt   用户提示词
     * @param outputType   结构化目标类型
     * @return {@link StructuredChatReply}：类型化结果 + 原始文本 + Token 用量
     */
    default <T> StructuredChatReply<T> chatStructured(String systemPrompt, String userPrompt, Class<T> outputType) {
        return chatStructured(systemPrompt, userPrompt, ParameterizedTypeReference.forType(outputType));
    }

    /**
     * 结构化输出（支持泛型目标类型，如 {@code List<TaskPlanStep>}）。
     * <p>解析失败自动重试一次（追加纠错指令再调），仍失败则抛出异常，由调用方决定回退。</p>
     *
     * @param systemPrompt 系统提示词（行为约束；形状由自动生成的 Schema 接管）
     * @param userPrompt   用户提示词
     * @param outputType   结构化目标类型（{@link ParameterizedTypeReference}，保留泛型信息）
     * @return {@link StructuredChatReply}：类型化结果 + 原始文本 + Token 用量
     */
    default <T> StructuredChatReply<T> chatStructured(String systemPrompt, String userPrompt,
                                                      ParameterizedTypeReference<T> outputType) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType, new ObjectMapper(), JSON_CLEANER);
        String system = systemPrompt + "\n" + converter.getFormat();
        int attempt = 0;
        while (true) {
            ChatReply reply = chatWithUsage(system, userPrompt);
            try {
                return new StructuredChatReply<>(converter.convert(reply.text()), reply.text(), reply.usage());
            } catch (Exception e) {
                attempt++;
                if (attempt >= 2) {
                    throw e;
                }
                system = system + "\n上次输出不符合 JSON Schema，请严格按上述 Schema 输出，不要多余文字，不要用代码块包裹。";
            }
        }
    }
}
