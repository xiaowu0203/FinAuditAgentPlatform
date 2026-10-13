package com.finaudit.toolservice.service;

import com.finaudit.starter.mq.message.ToolExecuteMessage;
import com.finaudit.starter.mq.message.ToolResultMessage;
import com.finaudit.toolservice.mq.ToolResultPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行缓存单测（P3.8 R6-3）。
 *
 * <p>两个断言：</p>
 * <ol>
 *   <li><b>缓存 key 必须含租户前缀</b>：否则两个租户用相同入参调用同一工具会命中同一条缓存
 *       ——第二个租户直接读到第一个租户的结果（跨租户数据泄漏）。</li>
 *   <li><b>Redis 故障必须降级</b>：缓存是性能优化的旁路，抖动时不得让工具执行失败
 *       （失败会导致不回吐 {@code tool.result}，agent-core 只能等任务级超时）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolExecutionServiceCacheTest {

    @Mock
    private ToolRegistryService registryService;
    @Mock
    private ToolResultPublisher resultPublisher;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private ToolExecutionLogService logService;

    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new ToolExecutionService(registryService, resultPublisher, redisTemplate, logService);
    }

    private void givenCacheableTool(String code, Map<String, Object> output) {
        when(registryService.isCacheable(code, 1L)).thenReturn(true);
        when(registryService.execute(code, 1L, 100L, Map.of("deptId", 9L))).thenReturn(output);
    }

    @Test
    void cacheKeyIsNamespacedByTenant() {
        givenCacheableTool("amount_verify", Map.of("match", true));
        // 租户 2 的同一工具、同一入参也必须走自己的 key
        when(registryService.isCacheable("amount_verify", 2L)).thenReturn(true);
        when(registryService.execute("amount_verify", 2L, 200L, Map.of("deptId", 9L)))
                .thenReturn(Map.of("match", true));

        service.executeAndPublish(new ToolExecuteMessage(100L, 1L, 1L, "amount_verify", Map.of("deptId", 9L)));
        service.executeAndPublish(new ToolExecuteMessage(200L, 2L, 2L, "amount_verify", Map.of("deptId", 9L)));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, org.mockito.Mockito.times(2)).set(keys.capture(), any(), any(java.time.Duration.class));

        String tenant1Key = keys.getAllValues().get(0);
        String tenant2Key = keys.getAllValues().get(1);
        assertTrue(tenant1Key.startsWith("tool:exec:1:amount_verify:"), "缓存 key 必须带租户前缀，实际=" + tenant1Key);
        assertTrue(tenant2Key.startsWith("tool:exec:2:amount_verify:"), "缓存 key 必须带租户前缀，实际=" + tenant2Key);
        assertNotEquals(tenant1Key, tenant2Key, "不同租户不得共用同一条缓存 key");
    }

    @Test
    void redisGetFailureDegradesToDirectExecution() {
        givenCacheableTool("amount_verify", Map.of("match", true));
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        service.executeAndPublish(new ToolExecuteMessage(100L, 1L, 1L, "amount_verify", Map.of("deptId", 9L)));

        // 仍真实执行了工具，并回吐成功结果（缓存读失败不得阻断主链路）
        verify(registryService).execute("amount_verify", 1L, 100L, Map.of("deptId", 9L));
        ArgumentCaptor<ToolResultMessage> msg = ArgumentCaptor.forClass(ToolResultMessage.class);
        verify(resultPublisher).publish(msg.capture());
        assertTrue(msg.getValue().success(), "缓存故障时工具仍应正常回吐成功结果");
    }

    @Test
    void redisSetFailureDoesNotBreakResult() {
        givenCacheableTool("amount_verify", Map.of("match", true));
        when(valueOperations.get(anyString())).thenReturn(null);
        doThrow(new RuntimeException("Redis timeout")).when(valueOperations).set(anyString(), any(), any(java.time.Duration.class));

        service.executeAndPublish(new ToolExecuteMessage(100L, 1L, 1L, "amount_verify", Map.of("deptId", 9L)));

        ArgumentCaptor<ToolResultMessage> msg = ArgumentCaptor.forClass(ToolResultMessage.class);
        verify(resultPublisher).publish(msg.capture());
        assertTrue(msg.getValue().success(), "缓存写失败不得影响结果回吐");
    }

    @Test
    void cacheHitSkipsToolExecution() {
        when(registryService.isCacheable("amount_verify", 1L)).thenReturn(true);
        when(valueOperations.get(anyString())).thenReturn(Map.of("match", true, "cached", true));
        when(registryService.execute(anyString(), anyLong(), anyLong(), any())).thenReturn(Map.of("match", true));

        service.executeAndPublish(new ToolExecuteMessage(100L, 1L, 1L, "amount_verify", Map.of("deptId", 9L)));

        org.mockito.Mockito.verify(registryService, org.mockito.Mockito.never())
                .execute(anyString(), anyLong(), anyLong(), any());
    }
}
