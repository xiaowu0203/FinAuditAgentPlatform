package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.InvoiceRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发票标识符投影 Mapper（仅被 {@code InvoiceRecordService} 持有，见 AGENTS.md §5.9）。
 * <p>查询/更新经 BaseMapper；唯一约束 {@code uk(tenant_id, invoice_code, invoice_num, deleted)}
 * 由数据库兜底并发重复投影。</p>
 */
@Mapper
public interface InvoiceRecordMapper extends BaseMapper<InvoiceRecord> {
}
