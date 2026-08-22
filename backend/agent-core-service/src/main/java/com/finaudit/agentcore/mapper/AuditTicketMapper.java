package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.AuditTicket;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审批工单表 Mapper（数据访问仅经 {@code AuditTicketService}，见 CLAUDE.md §5.8）。
 */
@Mapper
public interface AuditTicketMapper extends BaseMapper<AuditTicket> {
}
