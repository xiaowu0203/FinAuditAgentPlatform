package com.finaudit.starter.web.feign.dto;

import java.math.BigDecimal;

/**
 * 部门预算（跨服务契约，agent-core 提供，tool-service 消费）。
 * <p>剩余额度由消费方计算（totalBudget - usedAmount）；预算未配置时 Feign 返回 data=null。</p>
 *
 * @param id          预算主键
 * @param deptName    部门
 * @param period      预算周期 YYYY-MM
 * @param totalBudget 预算总额
 * @param usedAmount  已用额度
 */
public record BudgetVO(Long id, String deptName, String period,
                       BigDecimal totalBudget, BigDecimal usedAmount) {

    /** 剩余额度（未配置/字段为空时按 0 兜底计算） */
    public BigDecimal remaining() {
        return (totalBudget == null ? BigDecimal.ZERO : totalBudget)
                .subtract(usedAmount == null ? BigDecimal.ZERO : usedAmount);
    }
}
