package com.finaudit.agentcore.pojo.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报销单号生成格式：R + yyyyMMddHHmmss(14) + 4 位随机 = 19 字符。
 */
class ReimbNoTest {

    @Test
    void format() {
        String no = ExpenseReimbursement.generateReimbNo();
        assertEquals(19, no.length());
        assertTrue(no.matches("^R\\d{18}$"), "报销单号格式不正确: " + no);
    }

    @Test
    void prefixConstant() {
        assertTrue(ExpenseReimbursement.generateReimbNo().startsWith("R"));
    }
}
