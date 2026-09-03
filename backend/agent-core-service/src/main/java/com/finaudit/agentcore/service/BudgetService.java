package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.mapper.BudgetMapper;
import com.finaudit.agentcore.pojo.entity.Budget;
import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import com.finaudit.starter.web.feign.TenantServiceFeign;
import com.finaudit.starter.web.feign.dto.BudgetVO;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 部门预算服务（budget 实体数据访问仅允许在本类，见 CLAUDE.md §5.8）。
 * <p>剩余额度计算交给消费方（{@link BudgetVO#remaining()}），本类只做按 部门+周期 查询
 * 与 budget_query 越权校验（P3.5b：以 reimb.dept_id 为权威，替代原「租户已知部门」猜测）。</p>
 */
@Service
public class BudgetService {

    private final BudgetMapper budgetMapper;
    private final ReimbursementService reimbursementService;
    private final TenantServiceFeign tenantServiceFeign;

    public BudgetService(BudgetMapper budgetMapper,
                         ReimbursementService reimbursementService,
                         TenantServiceFeign tenantServiceFeign) {
        this.budgetMapper = budgetMapper;
        this.reimbursementService = reimbursementService;
        this.tenantServiceFeign = tenantServiceFeign;
    }

    /**
     * 按部门 + 预算周期查询（旧契约，部门名为提交快照——兼容存量任务；dept_name 冗余列）。
     */
    public BudgetVO findByDeptPeriod(Long tenantId, String deptName, String period) {
        Budget budget = budgetMapper.selectOne(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getTenantId, tenantId)
                .eq(Budget::getDeptName, deptName)
                .eq(Budget::getPeriod, period)
                .last("LIMIT 1"));
        return toVO(budget);
    }

    /**
     * 按部门 ID + 预算周期查询（P3.5b 权威关联键，预算行唯一键已切换 (tenant_id, dept_id, period)）。
     */
    public BudgetVO findByDeptIdPeriod(Long tenantId, Long deptId, String period) {
        if (deptId == null) {
            return null;
        }
        Budget budget = budgetMapper.selectOne(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getTenantId, tenantId)
                .eq(Budget::getDeptId, deptId)
                .eq(Budget::getPeriod, period)
                .last("LIMIT 1"));
        return toVO(budget);
    }

    /**
     * budget_query 越权校验（P3.5b 销 P3c TODO）：
     * <ul>
     *   <li>有 reimbId：驳回单 dept_id 与请求 dept_id 一致（提交者本人部门语义）；驳回单无部门（存量/未绑定）→ 放行。</li>
     *   <li>无 reimbId（HTTP 调试直调）：仅校验 sys_dept 存在性（经 tenant-service）。</li>
     * </ul>
     */
    public boolean isBudgetQueryAllowed(Long tenantId, Long reimbId, Long deptId) {
        if (reimbId != null) {
            // 获取报销单
            ExpenseReimbursement reimb = reimbursementService.getByReimbId(reimbId);
            // 报销单为空 || 租户ID不对，直接返回false
            if (reimb == null || !Objects.equals(tenantId, reimb.getTenantId())) {
                return false;
            }
            // reimb 无部门（存量/未绑定部门提交）：结构上无法判定归属，放行（沿用提交时无 deptId 的宽松语义）
            if (reimb.getDeptId() == null) {
                return true;
            }
            // 判定部门ID是否一致
            return Objects.equals(reimb.getDeptId(), deptId);
        }
        return deptId != null && Boolean.TRUE.equals(safeDeptExists(tenantId, deptId));
    }

    /**
     * 校验部门是否存在且启用
     * @param tenantId 租户ID
     * @param deptId 部门ID
     * @return 部门是否存在且启用
     */
    private Boolean safeDeptExists(Long tenantId, Long deptId) {
        try {
            return tenantServiceFeign.deptExists(tenantId, deptId).getData();
        } catch (Exception e) {
            // 部门服务不可达视为不存在（越权校验 fail-closed）
            return false;
        }
    }

    private static BudgetVO toVO(Budget budget) {
        if (budget == null) {
            return null;
        }
        return new BudgetVO(budget.getId(), budget.getDeptName(), budget.getPeriod(),
                budget.getTotalBudget(), budget.getUsedAmount());
    }
}