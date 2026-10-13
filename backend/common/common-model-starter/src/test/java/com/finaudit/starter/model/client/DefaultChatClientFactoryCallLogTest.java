package com.finaudit.starter.model.client;

import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.metrics.ModelCallContext;
import com.finaudit.starter.model.metrics.ModelCallRecord;
import com.finaudit.starter.model.metrics.ModelCallRecorder;
import com.finaudit.starter.model.metrics.ModelUsageSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用台账记录单测（P3.8 R9-1）。
 *
 * <p>验证「每次调用都留下一条可用台账」：成功记 token、失败记原因且 token 为 0、
 * 故障切换记实际生效的模型并打 fallback 标记、上下文里的任务/步骤正确落到记录上。</p>
 */
class DefaultChatClientFactoryCallLogTest {

    /** 可编排行为的假客户端：按需返回用量或抛异常 */
    private static final class FakeClient implements AiClient {
        private final ModelType type;
        private final TokenUsage usage;
        private final boolean fail;

        FakeClient(ModelType type, TokenUsage usage, boolean fail) {
            this.type = type;
            this.usage = usage;
            this.fail = fail;
        }

        @Override
        public ModelType getModelType() {
            return type;
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt) {
            if (fail) {
                throw new IllegalStateException("模型不可用");
            }
            return new ChatReply("ok", usage);
        }
    }

    private static final class CapturingRecorder implements ModelCallRecorder {
        final List<ModelCallRecord> calls = new ArrayList<>();

        @Override
        public void record(ModelCallRecord call) {
            calls.add(call);
        }
    }

    private static DefaultChatClientFactory factory(Map<ModelType, AiClient> clients,
                                                    ModelType fallback, ModelCallRecorder recorder) {
        return new DefaultChatClientFactory(clients, ModelType.DEEPSEEK, fallback, "deepseek-chat", recorder);
    }

    private static Map<ModelType, AiClient> clients(AiClient primary, AiClient backup) {
        Map<ModelType, AiClient> m = new EnumMap<>(ModelType.class);
        m.put(ModelType.DEEPSEEK, primary);
        if (backup != null) {
            m.put(backup.getModelType(), backup);
        }
        return m;
    }

    @Test
    void successfulCallIsRecordedWithTokensAndContext() {
        CapturingRecorder recorder = new CapturingRecorder();
        DefaultChatClientFactory f = factory(
                clients(new FakeClient(ModelType.DEEPSEEK, new TokenUsage(120, 30), false), null), null, recorder);

        ModelCallContext.runWith(1L, 400L, 55L, ModelCallContext.SCENE_LLM_STEP,
                () -> f.getClient(ModelType.DEEPSEEK).chatWithUsage("s", "u"));

        assertEquals(1, recorder.calls.size());
        ModelCallRecord call = recorder.calls.get(0);
        assertEquals(ModelType.DEEPSEEK, call.modelType());
        assertEquals("deepseek-chat", call.modelName());
        assertEquals(ModelCallContext.SCENE_LLM_STEP, call.scene());
        assertEquals(1L, call.tenantId());
        assertEquals(400L, call.taskId());
        assertEquals(55L, call.stepId());
        assertEquals(120, call.promptTokens());
        assertEquals(30, call.completionTokens());
        assertEquals(150, call.totalTokens());
        assertTrue(call.success());
        assertFalse(call.fallbackUsed());
        assertTrue(call.latencyMs() >= 0);
    }

    @Test
    void failedCallIsRecordedWithZeroTokensAndError() {
        CapturingRecorder recorder = new CapturingRecorder();
        DefaultChatClientFactory f = factory(
                clients(new FakeClient(ModelType.DEEPSEEK, TokenUsage.ZERO, true), null), null, recorder);

        assertThrows(IllegalStateException.class,
                () -> f.getClient(ModelType.DEEPSEEK).chatWithUsage("s", "u"));

        assertEquals(1, recorder.calls.size());
        ModelCallRecord call = recorder.calls.get(0);
        assertFalse(call.success(), "失败调用必须留痕（失败的调用往往才是最需要看的）");
        assertEquals(0, call.totalTokens());
        assertEquals("模型不可用", call.errorMsg());
        assertEquals(1, f.usageSnapshot().failed());
    }

    @Test
    void fallbackCallRecordsEffectiveModelAndFlag() {
        CapturingRecorder recorder = new CapturingRecorder();
        AiClient backup = new FakeClient(ModelType.QWEN, new TokenUsage(10, 5), false);
        DefaultChatClientFactory f = factory(
                clients(new FakeClient(ModelType.DEEPSEEK, TokenUsage.ZERO, true), backup),
                ModelType.QWEN, recorder);

        f.getClient(ModelType.DEEPSEEK).chatWithUsage("s", "u");

        assertEquals(1, recorder.calls.size());
        ModelCallRecord call = recorder.calls.get(0);
        // 记「实际生效」的模型类型，否则成本会算到没真正干活的模型头上
        assertEquals(ModelType.QWEN, call.modelType());
        assertTrue(call.fallbackUsed());
        assertTrue(call.success());
        assertEquals(15, call.totalTokens());
        assertEquals(1, f.usageSnapshot().fallbackCalls());
    }

    @Test
    void recorderFailureDoesNotBreakModelCall() {
        ModelCallRecorder broken = call -> {
            throw new RuntimeException("DB 挂了");
        };
        DefaultChatClientFactory f = factory(
                clients(new FakeClient(ModelType.DEEPSEEK, new TokenUsage(1, 1), false), null), null, broken);

        // 台账是旁路：记账失败绝不能让一次成功的模型调用变成失败
        ChatReply reply = f.getClient(ModelType.DEEPSEEK).chatWithUsage("s", "u");
        assertEquals("ok", reply.text());
    }

    @Test
    void noRecorderStillCountsInMemory() {
        DefaultChatClientFactory f = factory(
                clients(new FakeClient(ModelType.DEEPSEEK, new TokenUsage(2, 3), false), null), null, null);

        f.getClient(ModelType.DEEPSEEK).chatWithUsage("s", "u");

        assertEquals(5, f.usageSnapshot().totalTokens());
        assertNotNull(f.usageSnapshot().summary());
    }

    /** R9-1 接出口：快照必须带默认模型信息与失败率，且接口默认实现不返回 null */
    @Test
    void snapshotCarriesModelInfoAndFailRate() {
        DefaultChatClientFactory f = factory(
                clients(new FakeClient(ModelType.DEEPSEEK, new TokenUsage(10, 10), false), null), null, null);
        f.getClient(ModelType.DEEPSEEK).chatWithUsage("s", "u");

        ModelUsageSnapshot snap = f.usageSnapshot();
        assertEquals(ModelType.DEEPSEEK, snap.modelType());
        assertEquals("deepseek-chat", snap.modelName());
        assertEquals(1, snap.calls());
        assertEquals(0d, snap.failRatePct());

        // 接口默认实现：自定义实现不统计时也返回空快照而非 null，调用方无需判空
        ChatClientFactory bare = type -> null;
        assertEquals(0, bare.usageSnapshot().totalTokens());
        assertEquals(0d, bare.usageSnapshot().failRatePct());
    }
}
