package com.finaudit.toolservice.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.toolservice.enums.ToolCode;
import com.finaudit.toolservice.enums.ToolEnabledStatus;
import com.finaudit.toolservice.executor.ToolExecutor;
import com.finaudit.toolservice.mapper.ToolRegistryMapper;
import com.finaudit.toolservice.pojo.dto.ToolRegistryRegisterRequest;
import com.finaudit.toolservice.pojo.entity.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工具契约（Schema）单测（P3.8 R6-4）。
 *
 * <p>覆盖两侧：</p>
 * <ul>
 *   <li><b>注册口</b>：非法/无强度的 Schema 在注册时就被拒（不要等某次真实调用被拦才发现契约写错）；</li>
 *   <li><b>执行口</b>：{@code output_schema} 存在时出参形状被校验——「工具静默给错数据」必须在工具边界暴露。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolSchemaContractTest {

    @Mock
    private ToolRegistryMapper registryMapper;
    @Mock
    private ToolAccessGuard accessGuard;

    private ToolRegistryService service;

    /** 最小可用执行器：返回固定出参，用于验证出参校验 */
    private static class StubExecutor implements ToolExecutor {
        private final Map<String, Object> output;

        StubExecutor(Map<String, Object> output) {
            this.output = output;
        }

        @Override
        public ToolCode toolCode() {
            return ToolCode.AMOUNT_VERIFY;
        }

        @Override
        public Map<String, Object> execute(Long tenantId, Map<String, Object> inputParams) {
            return output;
        }
    }

    private ToolRegistry registered(ToolCode code, String toolCode, Map<String, Object> outputSchema,
                                    Map<String, Object> output) {
        ToolRegistry reg = new ToolRegistry();
        reg.setId(1L);
        reg.setTenantId(1L);
        reg.setToolCode(toolCode);
        reg.setEnabled(ToolEnabledStatus.ENABLED);
        reg.setCacheable(0);
        reg.setInputSchema(Map.of("type", "object", "properties", Map.of("items", Map.of("type", "array"))));
        reg.setOutputSchema(outputSchema);
        when(registryMapper.selectOne(any(Wrapper.class))).thenReturn(reg);
        return reg;
    }

    private void newService(Map<String, Object> executorOutput) {
        service = new ToolRegistryService(registryMapper, List.of(new StubExecutor(executorOutput)), accessGuard);
    }

    // ---------------- 注册口：Schema 合法性与强度 ----------------

    @Test
    void registerRejectsSchemaWithoutObjectType() {
        newService(Map.of());
        ToolRegistryRegisterRequest req = new ToolRegistryRegisterRequest("amount_verify", "金额核验", null,
                Map.of("properties", Map.of("items", Map.of("type", "array"))), null, null, null, null, null);

        BizException e = assertThrows(BizException.class, () -> service.register(req, 1L));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("type=object"), e.getMessage());
    }

    @Test
    void registerRejectsSchemaWithEmptyProperties() {
        newService(Map.of());
        ToolRegistryRegisterRequest req = new ToolRegistryRegisterRequest("amount_verify", "金额核验", null,
                Map.of("type", "object", "properties", Map.of()), null, null, null, null, null);

        BizException e = assertThrows(BizException.class, () -> service.register(req, 1L));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("properties"), e.getMessage());
    }

    @Test
    void registerRejectsUnknownToolCode() {
        newService(Map.of());
        ToolRegistryRegisterRequest req = new ToolRegistryRegisterRequest("no_such_tool", "x", null,
                Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string"))), null, null, null, null, null);

        assertThrows(BizException.class, () -> service.register(req, 1L));
    }

    @Test
    void registerAcceptsWellFormedSchema() {
        newService(Map.of());
        when(registryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(registryMapper.insert(any(ToolRegistry.class))).thenReturn(1);
        ToolRegistryRegisterRequest req = new ToolRegistryRegisterRequest("amount_verify", "金额核验", null,
                Map.of("type", "object", "properties", Map.of("items", Map.of("type", "array"))),
                Map.of("type", "object", "properties", Map.of("match", Map.of("type", "boolean"))),
                null, null, null, null);

        ToolRegistry saved = assertDoesNotThrow(() -> service.register(req, 1L));
        assertEquals("amount_verify", saved.getToolCode());
        assertEquals(Map.of("type", "object", "properties", Map.of("match", Map.of("type", "boolean"))),
                saved.getOutputSchema(), "出参 Schema 应一并落库");
    }

    // ---------------- 执行口：出参校验 ----------------

    @Test
    void executeRejectsOutputViolatingSchema() {
        // 出参缺 required 的 match 字段 → 必须拦（这正是「工具静默给错数据」的检测点）
        registered(ToolCode.AMOUNT_VERIFY, "amount_verify",
                Map.of("type", "object", "required", List.of("match"),
                        "properties", Map.of("match", Map.of("type", "boolean"))),
                Map.of("total", 100));
        newService(Map.of("total", 100));

        BizException e = assertThrows(BizException.class, () -> service.execute("amount_verify",
                ToolTenantCredential.mq(1L, 100L), Map.of("items", List.of(), "claimedTotal", 100)));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("工具出参校验失败"), e.getMessage());
    }

    @Test
    void executePassesOutputMatchingSchema() {
        registered(ToolCode.AMOUNT_VERIFY, "amount_verify",
                Map.of("type", "object", "required", List.of("match"),
                        "properties", Map.of("match", Map.of("type", "boolean"))),
                Map.of("match", true, "total", 100));
        newService(Map.of("match", true, "total", 100));

        Map<String, Object> out = service.execute("amount_verify",
                ToolTenantCredential.mq(1L, 100L), Map.of("items", List.of(), "claimedTotal", 100));
        assertEquals(true, out.get("match"));
    }

    @Test
    void executeSkipsOutputValidationWhenNoSchema() {
        // 存量工具无 output_schema：保持兼容，不校验
        registered(ToolCode.AMOUNT_VERIFY, "amount_verify", null, Map.of("anything", 1));
        newService(Map.of("anything", 1));

        assertDoesNotThrow(() -> service.execute("amount_verify",
                ToolTenantCredential.mq(1L, 100L), Map.of("items", List.of(), "claimedTotal", 100)));
    }

    @Test
    void executeRejectsInputViolatingSchema() {
        ToolRegistry reg = registered(ToolCode.AMOUNT_VERIFY, "amount_verify", null, Map.of());
        reg.setInputSchema(Map.of("type", "object", "required", List.of("items"),
                "properties", Map.of("items", Map.of("type", "array"))));
        newService(Map.of());

        BizException e = assertThrows(BizException.class, () -> service.execute("amount_verify",
                ToolTenantCredential.mq(1L, 100L), Map.of("claimedTotal", 100)));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("工具入参校验失败"), e.getMessage());
    }
}
