package com.finaudit.agentcore.domain;

import com.finaudit.agentcore.enums.StepType;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 流水线步骤声明（P3.8 R6-5）。
 *
 * <p><b>为什么要把流水线声明化</b>：原 {@code RuleBasedFlowEngine.plan()} 用一串
 * {@code if (…) steps.add(new TaskPlanStep(…))} 把「步骤顺序、条件、入参投影」混在一个方法里——
 * 结构只能靠读代码还原，加/删一步要改多处，也无法回答「这条流水线到底有几步、每步依赖什么」。
 * 抽成声明后，流水线本身就是一份可读的数据，来源可后续替换为 Nacos 配置（本轮只做 Java 内默认定义，
 * 不引入 DDL 表，避免过度设计）。</p>
 *
 * @param order      声明顺序（相对序号，从 1 开始；物化时按此升序）
 * @param stepName   步骤名称（落库与前端展示）
 * @param stepType   步骤类型（TOOL 工具调用 / LLM 大模型语义步骤）
 * @param toolName   TOOL 步骤的工具编码；LLM 步骤为 null
 * @param agentRole  执行角色（{@code AgentRole} 枚举名，可空；声明式定义之外的任务不允许指派角色）
 * @param condition  条件步骤判据：入参不满足则不生成该步骤（如「无附件不生成 OCR 步骤」）
 * @param projection 入参投影：从任务入参投影出该步骤工具所需的最小入参
 */
public record FlowStepDefinition(
        int order,
        String stepName,
        StepType stepType,
        String toolName,
        String agentRole,
        Predicate<Map<String, Object>> condition,
        Function<Map<String, Object>, Map<String, Object>> projection) {

    /** 条件步骤便捷构造：满足条件才生成 */
    public static FlowStepDefinition conditional(int order, String stepName, StepType stepType, String toolName,
                                                 String agentRole,
                                                 Predicate<Map<String, Object>> condition,
                                                 Function<Map<String, Object>, Map<String, Object>> projection) {
        return new FlowStepDefinition(order, stepName, stepType, toolName, agentRole, condition, projection);
    }

    /** 必选步骤便捷构造：无条件生成 */
    public static FlowStepDefinition always(int order, String stepName, StepType stepType, String toolName,
                                            String agentRole,
                                            Function<Map<String, Object>, Map<String, Object>> projection) {
        return new FlowStepDefinition(order, stepName, stepType, toolName, agentRole, input -> true, projection);
    }

    /** 该步骤在当前入参下是否生成 */
    public boolean appliesTo(Map<String, Object> input) {
        return condition == null || condition.test(input == null ? Map.of() : input);
    }

    /** 物化为可落库的规划步骤（条件不满足返回 null，由调用方跳过） */
    public TaskPlanStep materialize(Map<String, Object> input) {
        if (!appliesTo(input)) {
            return null;
        }
        Map<String, Object> params = projection == null ? null : projection.apply(input == null ? Map.of() : input);
        return new TaskPlanStep(stepName, stepType.name(), toolName, params, agentRole);
    }
}
