package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.BudgetOccupancy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预算占用记账 Mapper（仅被 {@code BudgetOccupancyService} 持有，见 AGENTS.md §5.9）。
 * <p>查询/状态更新全部经 BaseMapper（{@code selectOne} + {@code updateById}），无自定义 SQL；
 * 记账行的唯一约束 {@code uk(tenant_id, reimb_id)} 由数据库兜底并发重复插入。</p>
 */
@Mapper
public interface BudgetOccupancyMapper extends BaseMapper<BudgetOccupancy> {
}
