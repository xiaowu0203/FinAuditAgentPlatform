package com.finaudit.agentcore.enums;

/**
 * 审批工单状态机（audit_ticket.status）。
 * <pre>
 * PENDING → APPROVED / REJECTED / TERMINATED
 *     │ ↘ WITHDRAWN（提交人撤回，直接生效）
 *     ↘ AMENDED（提交人修改重跑，rerun_count+1）
 *         ├ 重跑再次命中 NEED_REVIEW → 复位 PENDING（review_reasons 刷新）
 *         ├ 重跑自动通过 → APPROVED
 *         └ 重跑失败 → onRerunFail 复位 PENDING（防死端）
 * APPROVED → WITHDRAW_PENDING（提交人发起撤销申请）
 *     ├ 财务同意 → WITHDRAWN（任务/报销单作废）
 *     └ 财务拒绝 → 回 APPROVED（原地返回）
 * </pre>
 */
public enum AuditTicketStatus {

    /** 待审批 */
    PENDING,
    /** 已通过 */
    APPROVED,
    /** 已驳回 */
    REJECTED,
    /** 修改重跑中（提交人已改明细，任务重跑） */
    AMENDED,
    /** 已终止 */
    TERMINATED,
    /** 撤销待审（提交人已发起撤销申请，等财务同意/拒绝） */
    WITHDRAW_PENDING,
    /** 已撤回或已撤销（任务/报销单作废 CANCELLED） */
    WITHDRAWN
}
