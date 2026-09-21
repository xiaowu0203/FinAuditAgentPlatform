package com.finaudit.starter.model.metrics;

import java.util.function.Supplier;

/**
 * 模型调用上下文（P3.8 R9-1）：把「这次调用属于哪个租户 / 任务 / 步骤 / 场景」传给模型工厂。
 *
 * <p><b>为什么用 ThreadLocal 而不是改接口签名</b>：{@code AiClient#chatWithUsage(system, user)} 是
 * 全链路通用抽象，为记账在每个调用点加 4 个参数会污染所有实现与调用方；而调用是同步的、
 * 且发起方（编排器）天然知道上下文。故采用与本仓 {@code TenantContextHolder} 一致的线程内传递方式，
 * 由调用方用 {@link #runWith} 包住调用（finally 清理，防线程池复用串号）。</p>
 *
 * <p>未设置上下文时（如直调/测试）所有字段为 null，台账仍会记录——只是没有任务维度关联。</p>
 */
public final class ModelCallContext {

    /** 调用场景默认值：模型步骤（编排器的主要调用形态） */
    public static final String SCENE_LLM_STEP = "llm_step";
    /** 调用场景：任务规划（GENERIC 由 LLM 拆解步骤） */
    public static final String SCENE_TASK_PLAN = "task_plan";

    private static final ThreadLocal<ModelCallContext> CURRENT = new ThreadLocal<>();

    private final Long tenantId;
    private final Long taskId;
    private final Long stepId;
    private final String scene;

    private ModelCallContext(Long tenantId, Long taskId, Long stepId, String scene) {
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.stepId = stepId;
        this.scene = scene;
    }

    public Long tenantId() {
        return tenantId;
    }

    public Long taskId() {
        return taskId;
    }

    public Long stepId() {
        return stepId;
    }

    public String scene() {
        return scene;
    }

    /** 当前线程的调用上下文；未设置返回 null */
    public static ModelCallContext current() {
        return CURRENT.get();
    }

    /**
     * 在指定上下文下执行动作，执行结束（含异常）自动清理。
     *
     * @param tenantId 租户 ID（可空）
     * @param taskId   任务 ID（可空）
     * @param stepId   步骤 ID（可空）
     * @param scene    场景标识（可空，缺省 {@link #SCENE_LLM_STEP}）
     */
    public static <T> T runWith(Long tenantId, Long taskId, Long stepId, String scene, Supplier<T> action) {
        ModelCallContext previous = CURRENT.get();
        CURRENT.set(new ModelCallContext(tenantId, taskId, stepId,
                scene == null || scene.isBlank() ? SCENE_LLM_STEP : scene));
        try {
            return action.get();
        } finally {
            // 恢复而非简单 remove：允许嵌套调用（如自校验内部再调模型）保持外层上下文
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 无返回值版本（规划器等场景） */
    public static void runWith(Long tenantId, Long taskId, Long stepId, String scene, Runnable action) {
        runWith(tenantId, taskId, stepId, scene, () -> {
            action.run();
            return null;
        });
    }
}
