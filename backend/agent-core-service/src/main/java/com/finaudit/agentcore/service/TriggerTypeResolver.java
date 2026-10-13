package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.ReviewFinding;

import java.util.List;

/**
 * 工单触发类型确定性映射（P3b 用户确认决策 3）。
 * <p>trigger_type 由复核原因/结构化问题项映射，纯代码判定不依赖 LLM：
 * 优先级 OVER_LIMIT &gt; RULE_FAIL &gt; RISK_HIT；LLM_DECISION 归 RISK_HIT 兜底。
 * 两种入口：{@link #resolve(List)} 按 reason 前缀（兼容历史数据），
 * {@link #resolveByFindings(List)} 按结构化 level（P3.8 R4 起推荐）。</p>
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

    /**
     * 按结构化问题项解析触发类型（P3.8 R4）。
     *
     * <p>与 {@link #resolve(List)} 同一优先级口径，但直接读 {@code level} 而非解析字符串前缀——
     * 结构化后无需再约定文本格式。优先级 OVER_LIMIT &gt; RULE_FAIL &gt; RISK_HIT。</p>
     *
     * @param findings 结构化问题项；空列表/仅 RISK_HIT 时归 RISK_HIT 兜底
     * @return trigger_type
     */
    public static String resolveByFindings(List<ReviewFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return RISK_HIT;
        }
        for (ReviewFinding f : findings) {
            if (f != null && OVER_LIMIT.equals(f.level())) {
                return OVER_LIMIT;
            }
        }
        for (ReviewFinding f : findings) {
            if (f != null && RULE_FAIL.equals(f.level())) {
                return RULE_FAIL;
            }
        }
        return RISK_HIT;
    }
}
