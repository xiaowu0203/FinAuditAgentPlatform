package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.ModelCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型调用台账 Mapper（仅被 {@code ModelCallLogService} 持有，见 AGENTS.md §5.9）。
 * <p>纯插入 + 聚合查询走 BaseMapper，无需自定义 XML（P3.8 R9-1）。</p>
 */
@Mapper
public interface ModelCallLogMapper extends BaseMapper<ModelCallLog> {
}
