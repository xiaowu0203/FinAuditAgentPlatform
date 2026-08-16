package com.finaudit.starter.mybatisplus.config;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归测试：MyBatis-Plus JSON 列携带 LocalDate 的序列化能力。
 * <p>P2a E2E 期间发现 {@code agent_task.input_params}（报销单快照，含 claimDate LocalDate）
 * 经默认 {@link JacksonTypeHandler} 序列化抛 {@code Java 8 date/time type not supported}；
 * 由 {@link CommonMybatisPlusAutoConfiguration#registerJacksonJsr310()} 注册 JavaTimeModule 修复。
 * 本测试锁定该行为，防止后续改动删掉注册。</p>
 */
class Jsr310JacksonTypeHandlerTest {

    @Test
    void shouldSerializeLocalDateInsideJsonMap() {
        // 与自动配置相同的 ObjectMapper 构造（保持测试与实现同步）
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        JacksonTypeHandler.setObjectMapper(mapper);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reimbId", 1L);
        input.put("claimDate", LocalDate.of(2026, 8, 15));

        String json = new JacksonTypeHandler(Map.class).toJson(input);
        assertEquals("{\"reimbId\":1,\"claimDate\":\"2026-08-15\"}", json);
    }
}
