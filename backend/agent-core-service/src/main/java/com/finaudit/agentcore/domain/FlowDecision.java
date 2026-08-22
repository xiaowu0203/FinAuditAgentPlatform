package com.finaudit.agentcore.domain;

import java.util.List;

/**
 * 流水线结果分支判定（P3a ReviewFlowDecider 输出）。
 * <p>分支为确定性代码判定（不靠 LLM 判分支）：AUTO_PASS 仅当全部工具干净 +
 * 风控不存疑 + LLM 结论 APPROVE；其余全部 NEED_REVIEW（终审权在人，P3b 审批工单统一入口）。</p>
 *
 * @param flowBranch    AUTO_PASS / NEED_REVIEW
 * @param reviewReasons 触发人工复核的原因列表，reason 前缀对齐 P3b 工单 trigger_type：
 *                      OVER_LIMIT / RULE_FAIL / RISK_HIT / LLM_DECISION（P3b 建工单按前缀取 trigger_type）
 */
public record FlowDecision(String flowBranch, List<String> reviewReasons) {

    public static final String AUTO_PASS = "AUTO_PASS";
    public static final String NEED_REVIEW = "NEED_REVIEW";

    public static FlowDecision autoPass() {
        return new FlowDecision(AUTO_PASS, List.of());
    }

    public static FlowDecision needReview(List<String> reasons) {
        return new FlowDecision(NEED_REVIEW, reasons);
    }
}
