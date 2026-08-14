package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务表 Mapper。
 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTask> {
}
