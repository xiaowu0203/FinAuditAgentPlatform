package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.StepStatus;
import com.finaudit.agentcore.mapper.AgentTaskStepMapper;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.agentcore.pojo.vo.StepVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务步骤服务：步骤实体（agent_task_step）的所有查询与更新均收敛于此。
 * <p>供 {@link AgentOrchestrator} 编排推进时调用，不再直接触碰 Mapper。</p>
 */
@Service
public class AgentTaskStepService {

    private final AgentTaskStepMapper stepMapper;

    public AgentTaskStepService(AgentTaskStepMapper stepMapper) {
        this.stepMapper = stepMapper;
    }

    /**
     * 按步骤 ID 查询，不存在返回 null（供工具结果回调"步骤缺失仅告警"）。
     */
    public AgentTaskStep findById(Long stepId) {
        return stepMapper.selectById(stepId);
    }

    public List<AgentTaskStep> listByTask(Long taskId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getTaskId, taskId)
                .orderByAsc(AgentTaskStep::getStepNo));
    }

    public List<StepVO> listVoByTask(Long taskId) {
        return listByTask(taskId).stream().map(StepVO::from).toList();
    }

    /**
     * 将规划步骤批量落库（沿用实体静态工厂 {@link AgentTaskStep#from}，单条多行 INSERT）。
     *
     * @param taskId   任务 ID
     * @param tenantId 租户 ID
     * @param plan     规划出的有序步骤
     */
    public void insertPlan(Long taskId, Long tenantId, List<TaskPlanStep> plan) {
        List<AgentTaskStep> steps = new ArrayList<>(plan.size());
        int no = 1;
        for (TaskPlanStep p : plan) {
            steps.add(AgentTaskStep.from(p, tenantId, taskId, no++));
        }
        if (!steps.isEmpty()) {
            stepMapper.insertBatch(steps);
        }
    }

    // ---------- 状态迁移 ----------

    /**
     * 步骤进入执行中（PENDING → RUNNING）。
     */
    public void markRunning(AgentTaskStep step) {
        step.setStatus(StepStatus.RUNNING.name());
        stepMapper.updateById(step);
    }

    /**
     * 步骤执行成功（→ SUCCESS），写入输出。
     */
    public void markSuccess(AgentTaskStep step, Map<String, Object> output) {
        step.setOutput(output);
        step.setStatus(StepStatus.SUCCESS.name());
        stepMapper.updateById(step);
    }

    /**
     * 步骤执行失败（→ FAILED），写入错误信息。
     */
    public void markFailed(AgentTaskStep step, String errorMsg) {
        step.setStatus(StepStatus.FAILED.name());
        step.setErrorMsg(errorMsg);
        stepMapper.updateById(step);
    }

    /**
     * 工具失败后重试（→ RUNNING），累计重试次数并记录错误信息。
     */
    public void markRetrying(AgentTaskStep step, int retryCount, String errorMsg) {
        step.setRetryCount(retryCount);
        step.setStatus(StepStatus.RUNNING.name());
        step.setErrorMsg(errorMsg);
        stepMapper.updateById(step);
    }

    /**
     * 断点续跑时，将残留 RUNNING 步骤重置为 PENDING（服务重启后无消息在途）。
     */
    public void resetRunningToPending(AgentTaskStep step) {
        step.setStatus(StepStatus.PENDING.name());
        stepMapper.updateById(step);
    }
}
