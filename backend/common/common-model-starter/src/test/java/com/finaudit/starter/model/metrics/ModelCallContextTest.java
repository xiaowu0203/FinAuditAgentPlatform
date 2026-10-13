package com.finaudit.starter.model.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模型调用上下文单测（P3.8 R9-1）。
 *
 * <p>它承载「这次调用属于哪个任务/步骤」的信息，一旦在线程池复用的线程上残留，
 * 台账就会把调用记到别的任务名下——成本统计会被污染且极难发现。故重点验证清理与嵌套语义。</p>
 */
class ModelCallContextTest {

    @Test
    void contextIsVisibleInsideAndClearedAfter() {
        assertNull(ModelCallContext.current(), "初始应无上下文");

        String scene = ModelCallContext.runWith(1L, 100L, 9L, ModelCallContext.SCENE_LLM_STEP, () -> {
            ModelCallContext ctx = ModelCallContext.current();
            assertEquals(1L, ctx.tenantId());
            assertEquals(100L, ctx.taskId());
            assertEquals(9L, ctx.stepId());
            assertEquals(ModelCallContext.SCENE_LLM_STEP, ctx.scene());
            return ctx.scene();
        });

        assertEquals(ModelCallContext.SCENE_LLM_STEP, scene);
        assertNull(ModelCallContext.current(), "退出后必须清理，防止线程池复用串任务");
    }

    @Test
    void contextIsClearedEvenWhenActionThrows() {
        try {
            ModelCallContext.runWith(1L, 100L, 9L, null, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // 预期异常
        }
        assertNull(ModelCallContext.current(), "异常路径同样必须清理");
    }

    @Test
    void blankSceneFallsBackToLlmStep() {
        ModelCallContext.runWith(1L, null, null, "   ", () -> {
            assertEquals(ModelCallContext.SCENE_LLM_STEP, ModelCallContext.current().scene());
            return null;
        });
    }

    @Test
    void nestedCallRestoresOuterContext() {
        ModelCallContext.runWith(1L, 100L, 9L, ModelCallContext.SCENE_LLM_STEP, () -> {
            // 内层：自校验等场景再调模型，应带上自己的场景
            ModelCallContext.runWith(1L, 100L, 9L, "self_check", () -> {
                assertEquals("self_check", ModelCallContext.current().scene());
                return null;
            });
            // 内层退出后恢复外层，而不是变成 null（否则外层的后续调用会丢任务关联）
            assertEquals(ModelCallContext.SCENE_LLM_STEP, ModelCallContext.current().scene(),
                    "内层结束后应恢复外层上下文");
            return null;
        });
    }

    @Test
    void runnableOverloadWorks() {
        AtomicReference<String> seen = new AtomicReference<>();
        ModelCallContext.runWith(2L, 200L, 7L, ModelCallContext.SCENE_TASK_PLAN,
                () -> seen.set(ModelCallContext.current().scene()));
        assertEquals(ModelCallContext.SCENE_TASK_PLAN, seen.get());
        assertNull(ModelCallContext.current());
    }
}
