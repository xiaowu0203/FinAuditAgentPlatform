package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.InvoiceReimbLink;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发票—报销单关联 Mapper（仅被 {@code InvoiceRecordService} 持有，见 AGENTS.md §5.9）。
 * <p>唯一约束 {@code uk_invoice_reimb(tenant_id, invoice_record_id, reimb_id, deleted)}
 * 由数据库兜底并发重复建立。</p>
 */
@Mapper
public interface InvoiceReimbLinkMapper extends BaseMapper<InvoiceReimbLink> {
}
