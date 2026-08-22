package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.mapper.BudgetMapper;
import com.finaudit.agentcore.pojo.entity.Budget;
import com.finaudit.starter.web.feign.dto.BudgetVO;
import org.springframework.stereotype.Service;

/**
 * 部门预算服务（budget 实体数据访问仅允许在本类，见 CLAUDE.md §5.8）。
 * <p>剩余额度计算交给消费方（{@link BudgetVO#remaining()}），本类只做按 部门+周期 查询。</p>
 */
@Service
public class BudgetService {

    private final BudgetMapper budgetMapper;

    public BudgetService(BudgetMapper budgetMapper) {
        this.budgetMapper = budgetMapper;
    }

    /**
     * 按部门 + 预算周期查询（多租户拦截器自动隔离 tenant_id）。
     *
     * @param tenantId 租户ID（仅供语义显式，WHERE 由拦截器注入）
     * @param deptName 部门
     * @param period   预算周期 YYYY-MM
     * @return 部门预算 VO；未配置返回 null
     */
    public BudgetVO findByDeptPeriod(Long tenantId, String deptName, String period) {
        Budget budget = budgetMapper.selectOne(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getTenantId, tenantId)
                .eq(Budget::getDeptName, deptName)
                .eq(Budget::getPeriod, period)
                .last("LIMIT 1"));
        if (budget == null) {
            return null;
        }
        return new BudgetVO(budget.getId(), budget.getDeptName(), budget.getPeriod(),
                budget.getTotalBudget(), budget.getUsedAmount());
    }

    /**
     * 校验部门是否属于当前租户
     * <p>以「该租户 budget 表是否出现过该部门名」为已知部门依据（无部门实体表，P5 再引入）。
     * 租户完全没有预算记录（新租户）时按放行处理，避免破坏合法流程。</p>
     *
     * @param tenantId 租户ID
     * @param deptName 待校验部门
     * @return true=该部门为租户已知部门（或无预算记录）；false=非本租户部门（越权/虚构）
     */
    public boolean isTenantDept(Long tenantId, String deptName) {
        if (deptName == null || deptName.isBlank()) {
            return false;
        }
        Long anyDeptCount = budgetMapper.selectCount(
                new LambdaQueryWrapper<Budget>().eq(Budget::getTenantId, tenantId));
        // 该租户完全没有预算记录：无法判定部门归属，放行（新租户/未配置预算场景）
        if (anyDeptCount == null || anyDeptCount == 0) {
            return true;
        }
        Long match = budgetMapper.selectCount(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getTenantId, tenantId)
                .eq(Budget::getDeptName, deptName));
        return match != null && match > 0;
    }
}
