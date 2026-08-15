package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.pojo.dto.TaskSubmitRequest;
import com.finaudit.agentcore.pojo.vo.TaskVO;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.mapper.AgentTaskMapper;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.starter.web.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 任务服务：任务实体（agent_task）的所有查询与更新均收敛于此。
 * <p>编排推进时由 {@link AgentOrchestrator} 调用本服务的查询与状态迁移方法，
 * 外部不直接触碰任务 Mapper。</p>
 */
@Service
public class AgentTaskService {

    private final AgentTaskMapper taskMapper;
    private final TaskEventPublisher eventPublisher;

    public AgentTaskService(AgentTaskMapper taskMapper, TaskEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.eventPublisher = eventPublisher;
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
        // 发布任务提交事件
        eventPublisher.publishTaskSubmit(task.getId(), tenantId);
        return TaskVO.from(task);
    }

    /**
     * 任务详情。
     */
    public TaskVO getTask(Long taskId) {
        return TaskVO.from(getRequired(taskId));
    }

    /**
     * 任务分页查询，status 为空查全部。
     */
    public Page<TaskVO> pageTask(int pageNum, int pageSize, String status) {
        LambdaQueryWrapper<AgentTask> wrapper = new LambdaQueryWrapper<AgentTask>()
                .orderByDesc(AgentTask::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(AgentTask::getStatus, status);
        }
        Page<AgentTask> page = taskMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<TaskVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(TaskVO::from).toList());
        return voPage;
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
     * 更新任务为失败状态，写入相关错误信息
     */
    public void markFailed(AgentTask task, String errorMsg) {
        task.setStatus(TaskStatus.FAILED.name());
        task.setErrorMsg(errorMsg);
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
