package com.finaudit.agentcore.enums;

import com.finaudit.starter.web.exception.BizException;

/**
 * 报销单审核状态（expense_reimbursement.status，对齐任务状态机）。
 */
public enum ReimbursementStatus {

    /** 待审核 */
    PENDING,
    /** 审核中 */
    RUNNING,
    /** 审核通过 */
    SUCCESS,
    /** 审核失败 */
    FAILED,
    /** 人工复核（P3 审批工单预留） */
    MANUAL_REVIEW;

    /** 解析，非法值抛业务异常 */
    public static ReimbursementStatus of(String v) {
        for (ReimbursementStatus s : values()) {
            if (s.name().equalsIgnoreCase(v)) {
                return s;
            }
        }
        throw new BizException("报销单状态不合法: " + v);
    }
}
