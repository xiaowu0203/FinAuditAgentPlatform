package com.finaudit.agentcore.mq;

import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.starter.mq.MqTopology;
import com.finaudit.starter.mq.message.TaskSubmitMessage;
import com.finaudit.starter.mq.message.ToolExecuteMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 任务事件发布器（agent-core → MQ）。
 */
@Component
public class TaskEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public TaskEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发布任务提交事件。
     * @param taskId 任务ID
     * @param tenantId 租户ID
     */
    public void publishTaskSubmit(Long taskId, Long tenantId) {
        rabbitTemplate.convertAndSend(MqTopology.EXCHANGE, MqTopology.ROUTING_TASK_SUBMIT,
                new TaskSubmitMessage(taskId, tenantId));
    }

    /**
     * 发布工具执行事件。
     * @param task 任务
     * @param step 任务步骤
     */
    public void publishToolExecute(AgentTask task, AgentTaskStep step) {
        rabbitTemplate.convertAndSend(MqTopology.EXCHANGE, MqTopology.ROUTING_TOOL_EXECUTE,
                new ToolExecuteMessage(task.getId(), step.getId(), task.getTenantId(),
                        step.getToolName(), step.getInputParams()));
    }
}
