package com.finaudit.agentcore.mq;

import com.finaudit.agentcore.service.AgentOrchestrator;
import com.finaudit.starter.mq.MqTopology;
import com.finaudit.starter.mq.message.ToolResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 工具结果消费者（tool.result）→ 推进任务状态机（成功续跑 / 失败重试）。
 */
@Component
@RabbitListener(queues = MqTopology.Q_TOOL_RESULT)
public class ToolResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultConsumer.class);

    private final AgentOrchestrator orchestrator;

    public ToolResultConsumer(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @RabbitHandler
    public void onToolResult(ToolResultMessage msg) {
        log.info("收到工具结果消息: taskId={}, stepId={}, toolCode={}, success={}",
                msg.taskId(), msg.stepId(), msg.toolCode(), msg.success());
        orchestrator.onToolResult(msg);
    }
}
