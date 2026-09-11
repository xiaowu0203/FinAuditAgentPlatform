package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.Budget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * 部门预算 Mapper（仅被 {@code BudgetOccupancyService} 持有，见 AGENTS.md §5.9）。
 *
 * <p>占用/释放必须是**单条原子 SQL**，禁止"读-算-写"：并发下两笔单据会各自读到相同的
 * {@code used_amount} 并分别判定"充足"，最终共同超支。故此处声明两条自定义 UPDATE，
 * SQL 见 {@code resources/mapper/BudgetMapper.xml}。</p>
 *
 * <p>与 {@code AgentTaskStepMapper} 同约定：自定义 SQL 通过 {@code @InterceptorIgnore(tenantLine="true")}
 * 关闭多租户自动拼接，租户条件在 SQL 内**显式书写**，避免依赖拦截器改写（也更利于并发正确性审查）。</p>
 */
@Mapper
public interface BudgetMapper extends BaseMapper<Budget> {

    /**
     * 原子占用：{@code used_amount += amount}，且**超支在 SQL 层拦死**
     * （{@code used_amount + amount <= total_budget}）。
     *
     * <p>影响行数为 0 表示两种可能，由调用方按业务语义处理：
     * <ul>
     *   <li>预算行不存在（部门+周期未配置预算）</li>
     *   <li>预算不足（加总后超总额）</li>
     * </ul>
     * 调用方需先用 {@link #findBudgetRow} 确认预算行是否存在，再据"行数=0"判定为不足。</p>
     *
     * @param tenantId 租户ID
     * @param deptId   部门ID
     * @param period   预算周期 YYYY-MM
     * @param amount   占用金额（正数）
     * @return 受影响行数（1=成功，0=预算不足或行不存在）
     */
    @InterceptorIgnore(tenantLine = "true")
    int occupy(@Param("tenantId") Long tenantId, @Param("deptId") Long deptId,
               @Param("period") String period, @Param("amount") BigDecimal amount);

    /**
     * 原子释放：{@code used_amount = GREATEST(used_amount - amount, 0)}。
     * <p>{@code GREATEST(...,0)} 防负数：释放语义上不应把 used_amount 扣成负值，
     * 即使出现重复释放（应用层已用记账表幂等，此处为数据库层兜底）。</p>
     *
     * @param tenantId 租户ID
     * @param deptId   部门ID
     * @param period   预算周期 YYYY-MM
     * @param amount   释放金额（正数）
     * @return 受影响行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int release(@Param("tenantId") Long tenantId, @Param("deptId") Long deptId,
                @Param("period") String period, @Param("amount") BigDecimal amount);

    /**
     * 查询预算行（**只读试算**用）：供占用前判断"预算未配置"与"额度是否充足"。
     * <p>不参与超支判定——超支由 {@link #occupy} 的原子 UPDATE 在数据库层拦死。
     * 此处仅用于把"预算不足"变成一次不写库的预检，避免在事务内捕获异常后继续写操作。</p>
     *
     * @param tenantId 租户ID
     * @param deptId   部门ID
     * @param period   预算周期 YYYY-MM
     * @return 预算行，不存在返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    Budget findBudgetRow(@Param("tenantId") Long tenantId, @Param("deptId") Long deptId,
                         @Param("period") String period);
}
