package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.Budget;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门预算 Mapper（仅被 BudgetService 持有，见 CLAUDE.md §5.8）。
 */
@Mapper
public interface BudgetMapper extends BaseMapper<Budget> {
}
