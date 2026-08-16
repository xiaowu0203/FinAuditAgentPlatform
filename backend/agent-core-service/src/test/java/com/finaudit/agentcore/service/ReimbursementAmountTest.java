package com.finaudit.agentcore.service;

import com.finaudit.agentcore.pojo.dto.ReimbursementItemRequest;
import com.finaudit.starter.web.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 报销总金额服务端求和（Decimal 强制，不信任客户端）。
 */
class ReimbursementAmountTest {

    private static ReimbursementItemRequest item(String name, String amount) {
        return new ReimbursementItemRequest(name, new BigDecimal(amount), null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void sumTwoItems() {
        BigDecimal total = ReimbursementService.computeTotal(
                List.of(item("高铁", "553.00"), item("住宿", "458.00")));
        assertEquals(0, new BigDecimal("1011.00").compareTo(total));
    }

    @Test
    void sumZero() {
        BigDecimal total = ReimbursementService.computeTotal(List.of(item("无票", "0.00")));
        assertEquals(0, BigDecimal.ZERO.compareTo(total));
    }

    @Test
    void emptyThrows() {
        assertThrows(BizException.class, () -> ReimbursementService.computeTotal(List.of()));
    }
}
