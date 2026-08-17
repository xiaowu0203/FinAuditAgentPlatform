package com.finaudit.agentcore.domain;

import java.util.Map;

/**
 * 规划的单个执行步骤。
 * <p>P3a 起承载执行角色 {@code agentRole}（AgentRole 枚举名），由
 * {@code RuleBasedFlowEngine}（REIMBURSEMENT）绑定真实角色；TaskPlanner（GENERIC，
 * LLM 拆解）输出时统一置 null——LLM 无权指派角色，避免其编造角色名。</p>
 * <p>注意：本 record 是 Spring AI {@code BeanOutputConverter} 结构化输出目标类型，
 * agentRole 用 String 而非 AgentRole 枚举，否则枚举 allowed values 会注入 schema 且
 * 反序列化严格，LLM 编造角色名会 convert 失败→纠错重试→回退模板。</p>
 *
 * @param stepName    步骤名称
 * @param stepType    LLM / TOOL
 * @param toolName    TOOL 步骤的工具编码
 * @param inputParams 步骤入参
 * @param agentRole   执行角色（AgentRole 枚举名，可空；GENERIC/LLM 规划为 null）
 */
public record TaskPlanStep(String stepName, String stepType,
                           String toolName, Map<String, Object> inputParams,
                           String agentRole) {

    /** 4 参便捷构造（agentRole 置 null），兼容 TaskPlanner fallback 与既有测试。 */
    public TaskPlanStep(String stepName, String stepType,
                        String toolName, Map<String, Object> inputParams) {
        this(stepName, stepType, toolName, inputParams, null);
    }
}
