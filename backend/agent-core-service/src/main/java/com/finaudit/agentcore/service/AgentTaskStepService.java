package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
    // P3.5d 起步骤状态迁移统一 CAS 化（WHERE id=? AND status=期望态）：
    // 步骤是 LLM 调用 / 工具执行的实际分发单元，CAS 失败即放弃执行，从根上杜绝
    // 多实例重复投递 / 迟到回调导致的「同一步骤双跑」（LLM 重复计费、工具重复执行）。

    /**
     * 步骤进入执行中（PENDING → RUNNING）。
     *
     * @return false = 步骤已被并发分发/迁移，调用方必须跳过执行
     */
    public boolean markRunning(AgentTaskStep step) {
        step.setStatus(StepStatus.RUNNING.name());
        return stepMapper.update(step, new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, step.getId())
                .eq(AgentTaskStep::getStatus, StepStatus.PENDING.name())) > 0;
    }

    /**
     * 步骤执行成功（RUNNING → SUCCESS），写入输出。
     *
     * @return false = 步骤状态已被并发迁移（如迟到结果/断点续跑重置），调用方应丢弃本次结果
     */
    public boolean markSuccess(AgentTaskStep step, Map<String, Object> output) {
        step.setOutput(output);
        step.setStatus(StepStatus.SUCCESS.name());
        return stepMapper.update(step, new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, step.getId())
                .eq(AgentTaskStep::getStatus, StepStatus.RUNNING.name())) > 0;
    }

    /**
     * 步骤执行失败（RUNNING → FAILED），写入错误信息。
     *
     * @return false = 步骤状态已被并发迁移，调用方应放弃任务级失败联动
     */
    public boolean markFailed(AgentTaskStep step, String errorMsg) {
        step.setStatus(StepStatus.FAILED.name());
        step.setErrorMsg(errorMsg);
        return stepMapper.update(step, new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, step.getId())
                .eq(AgentTaskStep::getStatus, StepStatus.RUNNING.name())) > 0;
    }

    /**
     * 工具失败后重试（RUNNING → RUNNING），累计重试次数并记录错误信息。
     *
     * @return false = 步骤状态已被并发迁移，调用方不应重发执行事件
     */
    public boolean markRetrying(AgentTaskStep step, int retryCount, String errorMsg) {
        step.setRetryCount(retryCount);
        step.setStatus(StepStatus.RUNNING.name());
        step.setErrorMsg(errorMsg);
        return stepMapper.update(step, new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, step.getId())
                .eq(AgentTaskStep::getStatus, StepStatus.RUNNING.name())) > 0;
    }

    /**
     * 断点续跑时，将残留 RUNNING 步骤重置为 PENDING（服务重启后无消息在途）。
     *
     * @return false = 步骤状态已被并发迁移
     */
    public boolean resetRunningToPending(AgentTaskStep step) {
        step.setStatus(StepStatus.PENDING.name());
        return stepMapper.update(step, new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, step.getId())
                .eq(AgentTaskStep::getStatus, StepStatus.RUNNING.name())) > 0;
    }

    /**
     * 写入步骤入参（P3.8 R5）：自纠错重跑风控步骤时，把矛盾提示放进该步入参，
     * 使 {@code executeLlmStep} 组装上下文时携带「自校验发现矛盾」的增强信息。
     *
     * <p><b>⚠️ 必须走实体补丁更新（R5-9）</b>：{@code input_params} 是 JSON 列，wrapper 的
     * {@code set(col, value)} 不带 typeHandler，驱动会按 binary 字符集发送字符串，MySQL 5.7 报
     * {@code Cannot create a JSON value from a string with CHARACTER SET 'binary'}。
     * 实测该异常曾让「自校验矛盾提示」注入静默失败（被兜底 catch 吞掉），
     * 见 {@link AgentTaskStep#inputParamsPatch}。</p>
     *
     * @param stepId      步骤 ID
     * @param inputParams 步骤入参
     * @return false = 未命中（步骤不存在）
     */
    public boolean updateInputParams(Long stepId, Map<String, Object> inputParams) {
        return stepMapper.update(AgentTaskStep.inputParamsPatch(stepId, inputParams),
                new LambdaUpdateWrapper<AgentTaskStep>()
                        .eq(AgentTaskStep::getId, stepId)) > 0;
    }

    /**
     * 自纠错重跑：将指定步骤重置为 PENDING 并清空输出/错误/重试计数（P3.8 R5）。
     *
     * <p>用于语义自校验命中矛盾后重跑风控语义步骤。因步骤编号靠后，其后的步骤（结论汇总等）
     * 也要一并重置，故按 stepNo 升序传入待重置步骤。</p>
     *
     * <p><b>⚠️ 必须同步内存对象（R5-8 踩过）</b>：调用方（{@code AgentOrchestrator.finalizeSuccess}）
     * 持有的 {@code steps} 列表与本方法入参是<b>同一批对象引用</b>。若只改数据库而不改内存，
     * 重置后下游再用这批对象判断「还有没有待执行步骤」时，看到的仍是 SUCCESS，
     * 于是判定「无可重置步骤」→ <b>重跑永远不会发生</b>（实测现象：自校验判定不一致、
     * 但 correction_count 恒为 0、工单原因里没有自校验项）。
     * 内存同步后，对象状态与 DB 一致，后续逻辑才能看到 PENDING。</p>
     *
     * <p><b>⚠️ 清空必须走 wrapper 的 {@code set(col, null)}</b>：把 null 放进实体再 update，
     * MyBatis-Plus 默认 NOT_NULL 策略会跳过该字段，输出与错误信息根本清不掉
     * （同 {@code AttachmentService.unbindByReimb} 踩过的坑）。</p>
     *
     * @param steps 待重置的步骤（调用方按 stepNo 升序传入）
     * @return 实际重置的步骤数
     */
    public int resetForSelfCorrection(List<AgentTaskStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        int reset = 0;
        for (AgentTaskStep step : steps) {
            reset += stepMapper.update(null, new LambdaUpdateWrapper<AgentTaskStep>()
                    .eq(AgentTaskStep::getId, step.getId())
                    // 仅重置已成功/失败的步骤；RUNNING 说明有在途执行，不动它
                    .in(AgentTaskStep::getStatus, StepStatus.SUCCESS.name(), StepStatus.FAILED.name())
                    .set(AgentTaskStep::getStatus, StepStatus.PENDING.name())
                    .set(AgentTaskStep::getOutput, null)
                    .set(AgentTaskStep::getErrorMsg, null)
                    .set(AgentTaskStep::getRetryCount, 0));
            // 同步内存对象：调用方持有同一批引用，不同步会让下游看到过期的 SUCCESS 状态
            step.setStatus(StepStatus.PENDING.name());
            step.setOutput(null);
            step.setErrorMsg(null);
            step.setRetryCount(0);
        }
        return reset;
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
