package com.finaudit.agentcore.enums;

import java.util.Set;

/**
 * 财务 Agent 角色（P3a 多 Agent 角色化，分派身份唯一真相）。
 * <p>角色定义收敛在本枚举：中文名 + 绑定工具编码集 + LLM 步骤 system prompt 片段。
 * 纯 TOOL 角色（DOCUMENT_PARSER / BUDGET_CALCULATOR / RULE_VALIDATOR）的"职责"体现在
 * {@code RuleBasedFlowEngine} 的流水线编排里，不参与 LLM 调用，故 systemPrompt 为空；
 * 只有 RISK_AUDITOR（风控语义判断）与 SCHEDULER（结论汇总）是真实 LLM 角色。</p>
 * <p>工具编码用字符串（不依赖 tool-service 的 {@code ToolCode} 枚举，避免跨模块依赖；
 * 编码值由工具目录持久化约束）。{@link #of(String)} 宽容解析：未知/空返回 null，
 * 兼容历史无角色步骤与 LLM 编造值，区别于 {@code ToolCode.of} 的抛异常语义。</p>
 */
public enum AgentRole {

    /** 调度汇总：统筹流水线，结论汇总 LLM 步骤 */
    SCHEDULER("统筹调度", Set.of(), """
            你是财务审核 Agent 的调度汇总角色。基于任务入参与前序步骤结果，给出确定性、可执行的审核结论。
            金额核验等工具已给出量化结果（match/total/diff）时，以该结果为准下结论，不要以"缺少单据/发票/审批材料"为由拒绝给出结论；
            若入参确实不足，明确指出缺少的具体字段，而非笼统索要材料。
            按下方 JSON Schema 输出结构化审核结论；decision 只能取 APPROVE（通过）/ REJECT（驳回）/ NEED_INFO（需补充材料）三者之一。"""),

    /** 票据解析：ocr_extract */
    DOCUMENT_PARSER("票据解析", Set.of("ocr_extract"), ""),

    /** 预算核算：budget_query */
    BUDGET_CALCULATOR("预算核算", Set.of("budget_query"), ""),

    /** 规则校验：rule_check + amount_verify */
    RULE_VALIDATOR("规则校验", Set.of("rule_check", "amount_verify"), ""),

    /** 风控审计：duplicate_check + 存疑语义判断 LLM 步骤 */
    RISK_AUDITOR("风控审计", Set.of("duplicate_check"), """
            你是报销风控审计 Agent。基于前序步骤（OCR 票据解析/金额核验/规则校验/重复检测）的量化输出，做语义层面的风控判断：
            综合票据真伪疑虑、金额异常、疑似重复、规则超标等信号，评估整体风险并给出风险等级。
            不确定时必须 uncertain=true 并降低 confidence，不得臆断；riskLevel 取 LOW/MEDIUM/HIGH 之一，
            confidence 取 0~1 的两位小数，riskPoints 列出具体风险点（可为空数组）。
            按下方 JSON Schema 输出结构化风控评估。""");

    /** 中文角色名（前端展示、日志） */
    private final String displayName;
    /** 绑定工具编码集（字符串，不依赖 tool-service 模块） */
    private final Set<String> toolCodes;
    /** LLM 步骤 system prompt 片段；纯 TOOL 角色为空串 */
    private final String systemPrompt;

    AgentRole(String displayName, Set<String> toolCodes, String systemPrompt) {
        this.displayName = displayName;
        this.toolCodes = toolCodes;
        this.systemPrompt = systemPrompt;
    }

    public String displayName() {
        return displayName;
    }

    public Set<String> toolCodes() {
        return toolCodes;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /** 宽容解析：null / 空白 / 未知角色名返回 null（区别于 {@code ToolCode.of} 抛异常）。 */
    public static AgentRole of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (AgentRole role : values()) {
            if (role.name().equalsIgnoreCase(name)) {
                return role;
            }
        }
        return null;
    }
}
