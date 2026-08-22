package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.AuditRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审批留痕表 Mapper（append-only，数据访问仅经 {@code AuditTicketService}，见 CLAUDE.md §5.8）。
 */
@Mapper
public interface AuditRecordMapper extends BaseMapper<AuditRecord> {
}
