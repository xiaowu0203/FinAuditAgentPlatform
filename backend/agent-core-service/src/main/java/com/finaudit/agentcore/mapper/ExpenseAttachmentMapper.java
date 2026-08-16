package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.ExpenseAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 报销业务附件 Mapper（仅被 AttachmentService 持有，见 CLAUDE.md §5.8）。
 */
@Mapper
public interface ExpenseAttachmentMapper extends BaseMapper<ExpenseAttachment> {

    /**
     * 批量新增（单条多行 INSERT，CLAUDE.md §5.9；SQL 见 resources/mapper/ExpenseAttachmentMapper.xml）。
     */
    int insertBatch(@Param("list") List<ExpenseAttachment> list);
}
