package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
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

    /**
     * 多租户插件不支持多行VALUES INSERT（批量操作），所以tenantLine设置为true，取消tenant_id自动填充，改为SQL显式写入
     */
    @InterceptorIgnore(tenantLine = "true")
    int insertBatch(@Param("list") List<AgentTaskStep> steps);
}
