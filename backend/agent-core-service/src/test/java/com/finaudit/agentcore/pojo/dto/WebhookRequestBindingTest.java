package com.finaudit.agentcore.pojo.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 请求体绑定 + 校验单测（P3.8 R8-2）。
 *
 * <p><b>为什么要测"绑定"这种看起来不用测的东西</b>：运行时实测发现
 * {@code POST /api/v1/notify/webhooks} 对**任何**请求体都返回 500（连"缺 name 应报 400 校验错"也变 500），
 * 而同服务其它 POST（提交报销单）正常——症状指向"请求体还没进 Service 就炸了"。
 * 本测试用 Spring 同款 ObjectMapper 与真实 Validator 复现该过程，
 * 把这类"框架层静默失败"从"需要看控制台堆栈"变成"单测里可复现"。</p>
 */
class WebhookRequestBindingTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        // Spring MVC 用的就是这个 builder（注册 ParameterNamesModule，record 才可反序列化）
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void fullBodyBindsAllFields() throws Exception {
        String json = "{\"name\":\"OA\",\"url\":\"https://example.com/hook\",\"secret\":\"k\","
                + "\"eventTypes\":[\"TICKET_APPROVED\"],\"enabled\":1,\"maxAttempts\":3,\"timeoutMs\":3000}";

        WebhookCreateRequest request = objectMapper.readValue(json, WebhookCreateRequest.class);

        assertNotNull(request);
        assertEquals("OA", request.name());
        assertEquals("https://example.com/hook", request.url());
        assertEquals(List.of("TICKET_APPROVED"), request.eventTypes());
        assertEquals(1, request.enabled());
        assertEquals(3, request.maxAttempts());
        assertEquals(3000, request.timeoutMs());
        assertTrue(validator.validate(request).isEmpty(), "完整合法请求体不应有校验错误");
    }

    @Test
    void minimalBodyBindsWithNullsAllowed() throws Exception {
        String json = "{\"name\":\"OA\",\"url\":\"https://example.com/hook\",\"secret\":\"k\"}";

        WebhookCreateRequest request = objectMapper.readValue(json, WebhookCreateRequest.class);

        assertEquals("OA", request.name());
        // 可选字段缺省为 null：JSR303 的 @Min/@Max 对 null 视为通过，由实体的缺省值兜底
        assertEquals(null, request.enabled());
        assertTrue(validator.validate(request).isEmpty(), "缺可选字段不应报校验错");
    }

    @Test
    void missingNameProducesReadableViolationInsteadOfThrowing() throws Exception {
        String json = "{\"url\":\"https://example.com/hook\",\"secret\":\"k\"}";

        WebhookCreateRequest request = objectMapper.readValue(json, WebhookCreateRequest.class);
        Set<ConstraintViolation<WebhookCreateRequest>> violations = validator.validate(request);

        // 运行时该场景返回的是 500 而非 400，故这里必须确认"校验器本身能正常工作"——
        // 若本断言通过而线上仍 500，说明问题不在 DTO/校验器，而在更外层（反序列化或过滤器）
        assertEquals(1, violations.size(), "缺 name 应产生 1 条校验错误，实际=" + violations);
        assertTrue(violations.iterator().next().getMessage().contains("配置名称不能为空"));
    }

    @Test
    void updateRequestBindsPartialBody() throws Exception {
        String json = "{\"url\":\"https://example.com/new\"}";

        WebhookUpdateRequest request = objectMapper.readValue(json, WebhookUpdateRequest.class);

        assertEquals("https://example.com/new", request.url());
        assertEquals(null, request.name(), "部分更新：未传字段保持 null");
        assertTrue(validator.validate(request).isEmpty());
    }
}
