package com.finaudit.agentcore.mq;

import com.finaudit.agentcore.service.AgentOrchestrator;
import com.finaudit.starter.mq.MqTopology;
import com.finaudit.starter.mq.message.TaskSubmitMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 任务提交消费者（task.submit）→ 触发编排启动。
 */
@Component
@RabbitListener(queues = MqTopology.Q_TASK_SUBMIT)
public class TaskSubmitConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskSubmitConsumer.class);

    private final AgentOrchestrator orchestrator;

    public TaskSubmitConsumer(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @RabbitHandler
    public void onTaskSubmit(TaskSubmitMessage msg) {
        log.info("收到任务提交消息: taskId={}, tenantId={}", msg.taskId(), msg.tenantId());
        // 执行任务
        orchestrator.start(msg.taskId());
    }
}
