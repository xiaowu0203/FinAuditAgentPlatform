package com.finaudit.agentcore.domain;

import java.util.List;

/**
 * 流水线结果分支判定（P3a ReviewFlowDecider 输出）。
 * <p>分支为确定性代码判定（不靠 LLM 判分支）：AUTO_PASS 仅当全部工具干净 +
 * 风控不存疑 + LLM 结论 APPROVE；其余全部 NEED_REVIEW（终审权在人，P3b 审批工单统一入口）。</p>
 *
 * <p><b>P3.8 R4 起同时携带结构化问题项</b>：{@link #findings} 是权威数据，
 * {@link #reviewReasons} 由 findings 派生（{@code "{LEVEL}:{说明}"}），
 * 保留它有三个既有用途：① {@code agent_task.result.reviewReasons} 展示；
 * ② 工单 {@code risk_desc} 字符串摘要；③ 工单 {@code trigger_type} 的前缀映射。</p>
 *
 * @param flowBranch    AUTO_PASS / NEED_REVIEW
 * @param findings      结构化问题项（权威，可为空列表表示无问题）
 * @param reviewReasons 问题原因串（由 findings 派生，兼容既有消费方）
 */
public record FlowDecision(String flowBranch, List<ReviewFinding> findings, List<String> reviewReasons) {

    public static final String AUTO_PASS = "AUTO_PASS";
    public static final String NEED_REVIEW = "NEED_REVIEW";

    public static FlowDecision autoPass() {
        return new FlowDecision(AUTO_PASS, List.of(), List.of());
    }

    /**
     * 由结构化问题项构造 NEED_REVIEW 决策，并派生 reasons。
     *
     * @param findings 结构化问题项
     */
    public static FlowDecision needReview(List<ReviewFinding> findings) {
        List<ReviewFinding> safe = findings == null ? List.of() : findings;
        return new FlowDecision(NEED_REVIEW, safe, safe.stream().map(ReviewFinding::toReason).toList());
    }

    /**
     * 兼容构造：仅给定原因串（无结构化问题项时使用，如历史代码路径）。
     */
    public static FlowDecision needReviewOfReasons(List<String> reasons) {
        List<String> safe = reasons == null ? List.of() : reasons;
        return new FlowDecision(NEED_REVIEW, List.of(), safe);
    }

    /** 是否存在问题项 */
    public boolean hasFindings() {
        return findings != null && !findings.isEmpty();
    }
}
