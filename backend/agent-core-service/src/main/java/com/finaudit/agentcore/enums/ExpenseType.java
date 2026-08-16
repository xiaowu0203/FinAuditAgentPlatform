package com.finaudit.agentcore.enums;

import com.finaudit.starter.web.exception.BizException;

/**
 * 费用类型（expense_reimbursement.expense_type）。
 */
public enum ExpenseType {

    /** 差旅 */
    TRAVEL,
    /** 招待 */
    ENTERTAINMENT,
    /** 办公 */
    OFFICE;

    /** 解析，非法值抛业务异常 */
    public static ExpenseType of(String v) {
        for (ExpenseType t : values()) {
            if (t.name().equalsIgnoreCase(v)) {
                return t;
            }
        }
        throw new BizException("费用类型不合法: " + v);
    }
}
