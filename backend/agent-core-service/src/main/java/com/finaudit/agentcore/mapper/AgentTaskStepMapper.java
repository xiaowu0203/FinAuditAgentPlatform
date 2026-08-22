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
     * 批量新增
     * 多租户插件不支持多行VALUES INSERT（批量操作），所以tenantLine设置为true，取消tenant_id自动填充，改为SQL显式写入
     */
    @InterceptorIgnore(tenantLine = "true")
    int insertBatch(@Param("list") List<AgentTaskStep> steps);

    /**
     * 软删任务全部步骤：{@code SET deleted = 该行主键 id}
     * <p>禁止用 MP 默认逻辑删（写 1）：uk_task_step 含 deleted，多轮重跑后同 (task_id, step_no, 1)
     * 会再次唯一冲突；置 deleted=id 则每行不同，历史步骤保留且不占唯一名额。
     * tenant_id 显式写入（多租户插件不支持该 UPDATE 的自动拼接）。</p>
     *
     * @param taskId   任务 ID
     * @param tenantId 租户 ID
     * @return 受影响行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int softDeleteByTaskId(@Param("taskId") Long taskId, @Param("tenantId") Long tenantId);
}
