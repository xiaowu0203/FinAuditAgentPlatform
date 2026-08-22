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

    /**
     * 全量重规划（P3b 工作流重设计）：逻辑删除旧步骤 + 按新规划重插。
     * <p>提交人修改明细后步骤可能增删（附件清空→OCR 步消失），update-in-place 只能置状态不能
     * 改结构，故必须全量重建；这也一并修复了旧实现「重跑时 TOOL 步骤沿用规划时投影的旧
     * input_params」的隐藏 bug（步骤重插后 inputParams 来自新任务快照）。</p>
     * <p>安全前提：仅允许任务非 RUNNING 时调用（resubmit 限定 ticket PENDING/REJECTED 保证无在途
     * tool.result 消息；旧步骤行逻辑删（deleted=1）保留，tool_execution_log 仍指向存在行）。
     * 调用方必须在 replan 后以 {@code AgentTaskService.markPlanned} 刷新任务 totalSteps。</p>
     *
     * @param taskId   任务 ID
     * @param tenantId 租户 ID
     * @param plan     新规划步骤（来自 {@code RuleBasedFlowEngine.plan} 对新 inputParams 的投影）
     */
    public void replan(Long taskId, Long tenantId, List<TaskPlanStep> plan) {
        // 软删置 deleted=id（而非 MP 默认 1）：uk_task_step 含 deleted，历史步骤保留且不占唯一名额
        stepMapper.softDeleteByTaskId(taskId, tenantId);
        insertPlan(taskId, tenantId, plan);
    }

    /**
     * amend 重跑步骤复位：全部步骤置 PENDING、清输出/错误、重试归零（流水线重跑）。
     *
     * @deprecated P3b 重设计后修改重跑统一走 {@link #replan}（全量重建，修复 inputParams 陈旧 bug）；
     *             本方法只能置状态、无法增删步骤，勿再用于 resubmit 链路。
     */
    @Deprecated
    public void resetAllForRerun(Long taskId) {
        for (AgentTaskStep step : listByTask(taskId)) {
            step.setStatus(StepStatus.PENDING.name());
            step.setOutput(null);
            step.setErrorMsg(null);
            step.setRetryCount(0);
            stepMapper.updateById(step);
        }
    }
}
