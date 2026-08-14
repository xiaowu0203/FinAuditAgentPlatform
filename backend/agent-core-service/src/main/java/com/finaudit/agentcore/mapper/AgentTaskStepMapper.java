package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 任务步骤表 Mapper。
 */
@Mapper
public interface AgentTaskStepMapper extends BaseMapper<AgentTaskStep> {
    int insertBatch(@Param("list") List<AgentTaskStep> steps);
}
