package com.finaudit.toolservice.mq;

import com.finaudit.starter.mq.MqTopology;
import com.finaudit.starter.mq.message.ToolResultMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 工具结果发布器（tool-service → MQ tool.result）。
 */
@Component
public class ToolResultPublisher {

    private final RabbitTemplate rabbitTemplate;

    public ToolResultPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发布工具执行结果
     * @param msg 工具执行结果
     */
    public void publish(ToolResultMessage msg) {
        rabbitTemplate.convertAndSend(MqTopology.EXCHANGE, MqTopology.ROUTING_TOOL_RESULT, msg);
    }
}
