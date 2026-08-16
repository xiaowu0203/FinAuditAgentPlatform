package com.finaudit.starter.web.feign.dto;

import java.util.Map;

/**
 * 工具目录项（跨服务 Feign 契约 DTO：tool-service 提供，消费方共用同一份定义，禁止各自复制）。
 *
 * @param toolCode    工具编码
 * @param toolName    工具名称
 * @param description 工具说明（LLM 理解工具用途）
 * @param inputSchema 入参 JSON Schema（LLM 规划工具入参）
 * @param scenario    业务场景（FINANCE/GENERIC，P2b TaskPlanner 按此收敛工具目录；null/blank 视为通用）
 */
public record ToolInfo(String toolCode, String toolName, String description,
                       Map<String, Object> inputSchema, String scenario) {

    /** 是否财务专用工具（用于业务收敛工具目录） */
    public boolean isFinance() {
        return "FINANCE".equalsIgnoreCase(scenario);
    }
}
