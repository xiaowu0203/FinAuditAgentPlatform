package com.finaudit.agentcore.service;

import com.finaudit.agentcore.mapper.ModelCallLogMapper;
import com.finaudit.agentcore.pojo.entity.ModelCallLog;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.metrics.ModelCallRecord;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型调用台账落库单测（P3.8 R9-1）。
 *
 * <p>两个重点：① SPI 记录 → 实体列的映射正确（tokens 汇总、布尔转 0/1、租户兜底）；
 * ② 落库失败必须被吞掉——台账是旁路，不能把一次成功的模型调用拖成失败。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelCallLogServiceTest {

    @Mock
    private ModelCallLogMapper mapper;

    private ModelCallLogService service() {
        return new ModelCallLogService(mapper);
    }

    private static ModelCallRecord record(Long tenantId) {
        return new ModelCallRecord(ModelType.DEEPSEEK, "deepseek-chat", "llm_step",
                tenantId, 400L, 55L, 120, 30, 4567L, true, false, null);
    }

    @Test
    void recordMapsAllFieldsIncludingTotalTokens() {
        when(mapper.insert(any(ModelCallLog.class))).thenReturn(1);

        service().record(record(1L));

        ArgumentCaptor<ModelCallLog> captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(mapper).insert(captor.capture());
        ModelCallLog log = captor.getValue();
        assertEquals(1L, log.getTenantId());
        assertEquals("DEEPSEEK", log.getModelType());
        assertEquals("deepseek-chat", log.getModelName());
        assertEquals("llm_step", log.getScene());
        assertEquals(400L, log.getTaskId());
        assertEquals(55L, log.getStepId());
        assertEquals(120, log.getPromptTokens());
        assertEquals(30, log.getCompletionTokens());
        // 总 tokens 冗余落列：指标 SQL 可直接 SUM(total_tokens)，不必每次写表达式
        assertEquals(150, log.getTotalTokens());
        assertEquals(4567L, log.getLatencyMs());
        assertEquals(1, log.getSuccess());
        assertEquals(0, log.getFallbackUsed());
    }

    @Test
    void failureCallIsPersistedWithZeroTokensAndError() {
        when(mapper.insert(any(ModelCallLog.class))).thenReturn(1);

        service().record(new ModelCallRecord(ModelType.DEEPSEEK, "deepseek-chat", "llm_step",
                1L, 400L, 55L, 0, 0, 30000L, false, false, "连接超时"));

        ArgumentCaptor<ModelCallLog> captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getSuccess(), "失败调用必须留痕（失败率指标的数据源）");
        assertEquals("连接超时", captor.getValue().getErrorMsg());
    }

    @Test
    void tenantFallsBackWhenRecordHasNoTenantAndNoContext() {
        when(mapper.insert(any(ModelCallLog.class))).thenReturn(1);

        service().record(record(null));

        ArgumentCaptor<ModelCallLog> captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(mapper).insert(captor.capture());
        // 无上下文直调时兜底默认租户，保证台账始终有租户归属（否则统计口径会漏掉这批调用）
        assertEquals(TenantContextHolder.DEFAULT_TENANT_ID, captor.getValue().getTenantId());
    }

    @Test
    void tenantContextIsPreferredOverDefault() {
        when(mapper.insert(any(ModelCallLog.class))).thenReturn(1);
        TenantContextHolder.setTenantId(9L);
        try {
            service().record(record(null));
        } finally {
            TenantContextHolder.clear();
        }

        ArgumentCaptor<ModelCallLog> captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals(9L, captor.getValue().getTenantId());
    }

    @Test
    void insertFailureIsSwallowed() {
        when(mapper.insert(any(ModelCallLog.class))).thenThrow(new RuntimeException("DB 连接池耗尽"));

        // 台账是旁路：落库异常绝不允许冒泡到模型工厂（否则成功的调用会被记账失败连累）
        assertDoesNotThrow(() -> service().record(record(1L)));
    }

    @Test
    void nullRecordIsIgnored() {
        assertDoesNotThrow(() -> service().record(null));
        verify(mapper, never()).insert(any(ModelCallLog.class));
    }

    /**
     * 实体边界按列长截断 errorMsg（真实库复现发现的缺陷：列 `VARCHAR(500)`，
     * 而 Starter 侧的 480 截断只覆盖"经模型工厂回调"这一条路径）。
     *
     * <p>若不截断，超长错误信息会让整行 insert 抛 {@code Data too long for column 'error_msg'}，
     * 再被本服务的兜底 catch 吞掉 —— <b>失败调用的台账被静默丢弃</b>，而它恰恰最需要留痕。</p>
     */
    @Test
    void overlongErrorMessageIsTruncatedAtEntityBoundary() {
        when(mapper.insert(any(ModelCallLog.class))).thenReturn(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            sb.append('x');
        }

        service().record(new ModelCallRecord(ModelType.DEEPSEEK, "deepseek-chat", "llm_step",
                1L, 400L, 55L, 0, 0, 1L, false, false, sb.toString()));

        ArgumentCaptor<ModelCallLog> captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals(ModelCallLog.ERROR_MSG_MAX_LEN, captor.getValue().getErrorMsg().length(),
                "errorMsg 必须截断到列上限，否则整行台账会因超长而丢失");
    }
}
