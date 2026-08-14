package com.finaudit.toolservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.toolservice.pojo.entity.ToolExecutionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具执行日志 Mapper。
 */
@Mapper
public interface ToolExecutionLogMapper extends BaseMapper<ToolExecutionLog> {
}
