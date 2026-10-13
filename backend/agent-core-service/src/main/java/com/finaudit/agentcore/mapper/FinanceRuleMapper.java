package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.FinanceRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 财务规则 Mapper（仅被 FinanceRuleService 持有，见 docs/architecture/conventions.md §1）。
 */
@Mapper
public interface FinanceRuleMapper extends BaseMapper<FinanceRule> {
}
