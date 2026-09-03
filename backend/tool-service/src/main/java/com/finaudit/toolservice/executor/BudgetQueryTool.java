package com.finaudit.toolservice.executor;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.feign.dto.BudgetVO;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.enums.ToolCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预算核算工具实现
 * <p>
 * 财务审核Agent工具：根据部门、报销日期、申报金额，查询部门月度预算，
 * 判断申报金额是否超过剩余预算额度，返回预算配置、占用、剩余以及超限判断结果
 * toolCode: {@link ToolCode#BUDGET_QUERY}
 * </p>
 */
@Component
public class BudgetQueryTool implements ToolExecutor {

    // Feign客户端，调用agent‑core核心服务，获取部门预算数据
    private final AgentCoreServiceFeign agentCoreServiceFeign;

    public BudgetQueryTool(AgentCoreServiceFeign agentCoreServiceFeign) {
        this.agentCoreServiceFeign = agentCoreServiceFeign;
    }

    @Override
    public ToolCode toolCode() {
        return ToolCode.BUDGET_QUERY;
    }

    /**
     * 执行预算查询逻辑
     * @param tenantId 租户ID，多租户隔离
     * @param inputParams 工具入参：deptName部门名称、claimDate报销日期(yyyy‑MM‑dd)、amount申报报销金额
     * @return 结果Map，包含预算配置状态、总额、已用、剩余、是否超限、提示文案
     * @throws BizException 入参缺失、远程调用异常时抛出业务异常
     */
    @Override
    public Map<String, Object> execute(Long tenantId, Map<String, Object> inputParams) {
        // 提取入参并做类型兼容转换
        String deptName = str(inputParams == null ? null : inputParams.get("deptName"));
        Long deptId = longValue(inputParams == null ? null : inputParams.get("deptId"));
        String claimDate = str(inputParams == null ? null : inputParams.get("claimDate"));
        BigDecimal amount = decimal(inputParams == null ? null : inputParams.get("amount"));

        // 校验必填入参：部门、报销日期、申报金额不能为空
        if ((deptName == null && deptId == null) || claimDate == null || amount == null) {
            throw new BizException("budget_query 入参缺少 deptName/deptId / claimDate / amount");
        }

        // 将报销日期 yyyy‑MM‑dd 截取为预算周期 yyyy‑MM（月度预算）
        String period = claimDate.length() >= 7 ? claimDate.substring(0, 7) : claimDate;

        // feign远程调用核心服务查询部门月度预算：P3.5b 起 deptId 为权威关联键，优先按 ID 查；存量按快照名查
        R<BudgetVO> resp = deptId != null
                ? agentCoreServiceFeign.queryBudgetByDeptId(tenantId, deptId, period)
                : agentCoreServiceFeign.queryBudget(tenantId, deptName, period);
        // 远程调用业务码非0，抛出业务异常
        if (resp.getCode() != 0) {
            throw new BizException("预算查询失败: " + resp.getMessage());
        }

        BudgetVO budget = resp.getData();
        Map<String, Object> result = new LinkedHashMap<>();

        // 该部门当月没有配置预算的场景
        if (budget == null) {
            result.put("configured", false);
            result.put("message", "部门[" + deptName + "] " + period + " 未配置预算");
            result.put("exceedsBudget", false);
            return result;
        }

        // 获取预算剩余额度，比较申报金额是否超出剩余预算
        BigDecimal remaining = budget.remaining();
        boolean exceeds = amount.compareTo(remaining) > 0;

        // 组装返回结果，有序map方便大模型读取解析
        result.put("configured", true);
        result.put("deptName", budget.deptName());
        result.put("period", budget.period());
        result.put("totalBudget", budget.totalBudget());
        result.put("usedAmount", budget.usedAmount());
        result.put("remaining", remaining);
        result.put("claimedAmount", amount);
        result.put("exceedsBudget", exceeds);
        result.put("message", exceeds
                ? "申报金额 " + amount.stripTrailingZeros().toPlainString()
                + " 超出部门剩余预算 " + remaining.stripTrailingZeros().toPlainString()
                : "预算充足，剩余 " + remaining.stripTrailingZeros().toPlainString());
        return result;
    }

    /**
     * 通用对象转字符串工具，null安全
     * @param v 任意对象
     * @return 对象toString结果，输入null返回null
     */
    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * 通用对象转 Long（SQLE deptId 入参兼容 Number/String），null/非法返回 null。
     */
    private static Long longValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 对象安全转换BigDecimal，兼容Number、String原始入参，转换失败返回null
     * <p>适配Agent工具传入的动态参数，避免类型转换异常</p>
     * @param v 原始参数对象
     * @return BigDecimal，解析失败返回null
     */
    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
