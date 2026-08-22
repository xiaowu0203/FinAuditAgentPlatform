package com.finaudit.agentcore.enums;

/**
 * 审批留痕动作（audit_record.action）。
 * <p>P3b 工作流重设计后语义：AMEND 由「财务改金额」改为「提交人修改明细重跑」（同单续跑，
 * 与 admin 原 amend 共用 rerun_count 计数器）；新增提交人撤回/撤销申请链路。</p>
 */
public enum AuditAction {

    /** 建单（NEED_REVIEW → 生成审批工单） */
    SUBMIT,
    /** 通过（财务） */
    APPROVE,
    /** 驳回（财务，→ 任务 REJECTED） */
    REJECT,
    /** 提交人修改明细重跑（PENDING/REJECTED → AMENDED，rerun_count+1） */
    AMEND,
    /** 终止（财务，→ 任务 REJECTED，errorMsg 记录终止） */
    TERMINATE,
    /** 重跑复位（重跑再次命中 NEED_REVIEW → 工单复位 PENDING） */
    RERUN,
    /** 重跑失败复位（重跑 FAILED → 工单复位 PENDING，防 AMENDED 死端） */
    RERUN_FAILED,
    /** 提交人撤回（PENDING → WITHDRAWN，直接生效） */
    WITHDRAW,
    /** 提交人发起撤销申请（APPROVED → WITHDRAW_PENDING） */
    WITHDRAW_REQ,
    /** 财务同意撤销（WITHDRAW_PENDING → WITHDRAWN，任务/报销单作废） */
    WITHDRAW_AGREE,
    /** 财务拒绝撤销（WITHDRAW_PENDING → APPROVED 原地返回） */
    WITHDRAW_REFUSE
}
