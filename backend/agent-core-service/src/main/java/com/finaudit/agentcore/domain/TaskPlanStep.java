package com.finaudit.agentcore.domain;

import java.util.Map;

/**
 * 规划的单个执行步骤。
 *
 * @param stepName    步骤名称
 * @param stepType    LLM / TOOL
 * @param toolName    TOOL 步骤的工具编码
 * @param inputParams 步骤入参
 */
public record TaskPlanStep(String stepName, String stepType,
                           String toolName, Map<String, Object> inputParams) {
}
