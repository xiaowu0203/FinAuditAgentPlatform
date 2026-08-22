package com.finaudit.agentcore.service;

import java.util.List;

/**
 * 工单触发类型确定性映射（P3b 用户确认决策 3）。
 * <p>trigger_type 由复核原因前缀映射，纯代码判定不依赖 LLM：
 * 优先级 OVER_LIMIT &gt; RULE_FAIL &gt; RISK_HIT；LLM_DECISION 归 RISK_HIT 兜底。
 * reason 格式为 "{PREFIX}:{描述}"（见 {@link ReviewFlowDecider} 输出）。</p>
 */
public final class TriggerTypeResolver {

    private TriggerTypeResolver() {
    }

    /** 大额超限（财务规则 AMOUNT_LIMIT 命中） */
    public static final String OVER_LIMIT = "OVER_LIMIT";
    /** 规则校验失败（超标/部门预算超支） */
    public static final String RULE_FAIL = "RULE_FAIL";
    /** 风控存疑（重复/金额不符/LLM 置信度低等） */
    public static final String RISK_HIT = "RISK_HIT";
    /** 描述截断上限（对齐 audit_ticket.risk_desc VARCHAR(512)） */
    private static final int RISK_DESC_MAX = 512;

    /**
     * 按前缀优先级解析触发类型：OVER_LIMIT &gt; RULE_FAIL &gt; RISK_HIT；
     * LLM_DECISION / 未知前缀 / 空列表一律归 RISK_HIT 兜底。
     */
    public static String resolve(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return RISK_HIT;
        }
        for (String reason : reasons) {
            if (reason != null && reason.startsWith(OVER_LIMIT + ":")) {
                return OVER_LIMIT;
            }
        }
        for (String reason : reasons) {
            if (reason != null && reason.startsWith(RULE_FAIL + ":")) {
                return RULE_FAIL;
            }
        }
        return RISK_HIT;
    }

    /**
     * 复核原因 join（"；"）并截断至 512 字符（超出尾部加省略号）。
     */
    public static String buildRiskDesc(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }
        String joined = String.join("；", reasons);
        if (joined.length() <= RISK_DESC_MAX) {
            return joined;
        }
        return joined.substring(0, RISK_DESC_MAX - 1) + "…";
    }
}
