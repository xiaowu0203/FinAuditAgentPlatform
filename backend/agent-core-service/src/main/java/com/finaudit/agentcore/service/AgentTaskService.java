package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.pojo.dto.TaskSubmitRequest;
import com.finaudit.agentcore.pojo.vo.StepVO;
import com.finaudit.agentcore.pojo.vo.TaskVO;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.mapper.AgentTaskMapper;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.starter.web.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

/**
 * 任务服务：任务实体（agent_task）的所有查询与更新均收敛于此。
 * <p>编排推进时由 {@link AgentOrchestrator} 调用本服务的查询与状态迁移方法，
 * 外部不直接触碰任务 Mapper。任务步骤的只读数据经 {@link AgentTaskStepService} 委托查询
 * （如 {@link #listSteps}），本类不直接触碰步骤 Mapper。</p>
 */
@Service
public class AgentTaskService {

    private final AgentTaskMapper taskMapper;
    private final TaskEventPublisher eventPublisher;
    private final AgentTaskStepService stepService;

    public AgentTaskService(AgentTaskMapper taskMapper, TaskEventPublisher eventPublisher,
                            AgentTaskStepService stepService) {
        this.taskMapper = taskMapper;
        this.eventPublisher = eventPublisher;
        this.stepService = stepService;
    }

    /**
     * 提交任务：类型转换并落库（初始状态 PENDING），发布任务提交事件。
     *
     * @param createdBy 创建人用户 ID（由请求头 X-User-Id 传入；为空时 created_by 不落值）
     */
    @Transactional
    public TaskVO createTask(TaskSubmitRequest request, Long tenantId, Long createdBy) {
        // 类型转换，初始化状态为已提交待执行
        AgentTask task = AgentTask.from(request, tenantId, createdBy);
        // 落库
        taskMapper.insert(task);
        // 发布任务提交事件：必须在事务提交【之后】发布，否则消费者可能在事务提交前
        // 抢先消费，读不到任务行报「任务不存在」（发布先于提交的竞态，已实测复现）。
        // 事务提交后触发 afterCommit，此时任务行（及外层报销单事务）对消费者可见；
        // 回滚则不触发，杜绝孤儿消息。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishTaskSubmit(task.getId(), tenantId);
                }

                @Override
                public void afterCompletion(int status) {
                    // 提交路径已由 afterCommit 发布；回滚无需处理
                }
            });
        } else {
            // 无事务上下文时兜底直接发布
            eventPublisher.publishTaskSubmit(task.getId(), tenantId);
        }
        return TaskVO.from(task);
    }

    /**
     * 任务详情（P3b 可见性统一）：非财务角色仅本人可查。
     */
    public TaskVO getTask(Long taskId, Long userId, boolean finance) {
        // 校验任务是否存在，不存在时抛出异常
        AgentTask task = getRequired(taskId);
        // 校验任务读可见（非财务角色仅本人创建，createdBy 为空的旧任务仅财务可见）
        requireVisible(task, userId, finance);
        return TaskVO.from(task);
    }

    /**
     * 任务步骤列表（P3b 可见性统一）：非财务角色仅本人任务可查。
     * <p>可见性校验 + 步骤查询合并为一次委托，避免 Controller 串联两个 Service；
     * 步骤数据经 {@link AgentTaskStepService#listVoByTask} 查询，本类不直接触碰步骤 Mapper。</p>
     */
    public List<StepVO> listSteps(Long taskId, Long userId, boolean finance) {
        // 校验任务读可见（非财务角色仅本人创建，createdBy 为空的旧任务仅财务可见）
        requireVisible(taskId, userId, finance);
        // 根据任务ID查询步骤VO列表
        return stepService.listVoByTask(taskId);
    }

    /**
     * 任务分页查询（P3b 可见性统一）：status 为空查全部；非财务角色仅本人创建，finance 看本租户全量。
     */
    public Page<TaskVO> pageTask(int pageNum, int pageSize, String status, Long userId, boolean finance) {
        LambdaQueryWrapper<AgentTask> wrapper = new LambdaQueryWrapper<AgentTask>()
                .orderByDesc(AgentTask::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(AgentTask::getStatus, status);
        }
        // 若非财务角色，则只能查看自己的数据
        if (!finance) {
            wrapper.eq(AgentTask::getCreatedBy, userId);
        }
        Page<AgentTask> page = taskMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<TaskVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(TaskVO::from).toList());
        return voPage;
    }

    /**
     * 校验任务读可见（P3b 可见性统一）：非财务角色仅本人创建，createdBy 为空的旧任务仅财务可见。
     * 供详情/步骤等只读接口复用，避免先取 VO 再抛异常的重复查询。
     */
    public void requireVisible(Long taskId, Long userId, boolean finance) {
        requireVisible(getRequired(taskId), userId, finance);
    }

    /**
     * 校验任务读可见（P3b 可见性统一）：非财务角色仅本人创建，createdBy 为空的旧任务仅财务可见。
     * @param task Agent 任务
     * @param userId 当前用户 ID
     * @param finance 是否财务角色
     */
    private void requireVisible(AgentTask task, Long userId, boolean finance) {
        if (!finance && (task.getCreatedBy() == null || !userId.equals(task.getCreatedBy()))) {
            throw new BizException("无权查看他人任务");
        }
    }

    /**
     * 查询任务实体，不存在时抛业务异常。
     */
    public AgentTask getRequired(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        return task;
    }

    // ---------- 状态迁移（编排器调用） ----------

    /**
     * 更新任务状态为执行中
     */
    public void markRunning(AgentTask task) {
        task.setStatus(TaskStatus.RUNNING.name());
        taskMapper.updateById(task);
    }

    /**
     * 更新任务总步骤数量，并将已完成步骤置0
     */
    public void markPlanned(AgentTask task, int totalSteps) {
        task.setTotalSteps(totalSteps);
        task.setFinishedSteps(0);
        taskMapper.updateById(task);
    }

    /**
     * 更新任务为成功状态，写入相应结果
     */
    public void markSuccess(AgentTask task, Map<String, Object> result, int finishedSteps) {
        task.setResult(result);
        task.setStatus(TaskStatus.SUCCESS.name());
        task.setFinishedSteps(finishedSteps);
        taskMapper.updateById(task);
    }

    /**
     * 更新任务为待审批（P3a 结果分支 NEED_REVIEW），写入流水线结果与进度。
     * <p>镜像 markSuccess，仅状态为 APPROVAL_PENDING；审批动作由 P3b 工单模块流转
     * （通过→SUCCESS、驳回→REJECTED）。</p>
     */
    public void markApprovalPending(AgentTask task, Map<String, Object> result, int finishedSteps) {
        task.setResult(result);
        task.setStatus(TaskStatus.APPROVAL_PENDING.name());
        task.setFinishedSteps(finishedSteps);
        taskMapper.updateById(task);
    }

    /**
     * 更新任务为失败状态，写入相关错误信息
     */
    public void markFailed(AgentTask task, String errorMsg) {
        task.setStatus(TaskStatus.FAILED.name());
        task.setErrorMsg(errorMsg);
        taskMapper.updateById(task);
    }

    /**
     * 更新任务为人工驳回终态
     */
    public void markRejected(AgentTask task) {
        task.setStatus(TaskStatus.REJECTED.name());
        taskMapper.updateById(task);
    }

    /**
     * 更新任务为终止终态。
     */
    public void markTerminated(AgentTask task) {
        task.setStatus(TaskStatus.REJECTED.name());
        task.setErrorMsg("审批工单终止");
        taskMapper.updateById(task);
    }

    /**
     * 更新任务为作废终态，记录原因供展示，
     * resume/onToolResult 均以 CANCELLED 拒绝，防迟到回调写脏状态。
     */
    public void markCancelled(AgentTask task, String reason) {
        task.applyCancelled(reason);
        taskMapper.updateById(task);
    }

    /**
     * amend 重跑准备、清空结果与错误、
     * 状态回 RUNNING、已完成步骤清零——单次 updateById 原子完成。
     * <p>由 {@code AuditTicketService.amend} 在工单动作事务内调用；重跑由编排器 continueTask 驱动
     * （Controller 在事务提交后触发），首个重跑步骤恒为 TOOL→MQ。</p>
     *
     * @param inputParams 修正后的任务快照入参（含新 items 与重算 claimedTotal）
     */
    public void prepareRerun(AgentTask task, Map<String, Object> inputParams) {
        task.setInputParams(inputParams);
        task.setResult(null);
        task.setErrorMsg(null);
        task.setStatus(TaskStatus.RUNNING.name());
        task.setFinishedSteps(0);
        taskMapper.updateById(task);
    }

    /**
     * 更新任务已完成数量
     */
    public void setFinishedSteps(AgentTask task, int finished) {
        task.setFinishedSteps(finished);
        taskMapper.updateById(task);
    }
}
