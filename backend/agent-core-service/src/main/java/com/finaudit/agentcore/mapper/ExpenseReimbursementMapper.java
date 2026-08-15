package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 报销单 Mapper（仅被 ReimbursementService 持有，见 CLAUDE.md §5.8）。
 */
@Mapper
public interface ExpenseReimbursementMapper extends BaseMapper<ExpenseReimbursement> {
}
