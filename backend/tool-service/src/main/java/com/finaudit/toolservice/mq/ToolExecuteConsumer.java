package com.finaudit.toolservice.mq;

import com.finaudit.starter.mq.MqTopology;
import com.finaudit.starter.mq.message.ToolExecuteMessage;
import com.finaudit.toolservice.service.ToolExecutionService;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 工具执行消息消费者（队列：tool.execute）→ 委托 {@link ToolExecutionService} 执行完整管线
 * （缓存 → 执行 → 落日志 → 发布 tool.result）。
 */
@Component
@RabbitListener(queues = MqTopology.Q_TOOL_EXECUTE)
public class ToolExecuteConsumer {

    private final ToolExecutionService toolExecutionService;

    public ToolExecuteConsumer(ToolExecutionService toolExecutionService) {
        this.toolExecutionService = toolExecutionService;
    }

    /**
     * RabbitMQ消息统一消费处理方法
     * 监听 tool.execute 队列，接收工具执行请求消息
     *
     * @param msg 工具执行请求消息体，包含任务ID、步骤ID、工具编码、入参、租户信息等
     */
    @RabbitHandler
    public void onToolExecute(ToolExecuteMessage msg) {
        toolExecutionService.executeAndPublish(msg);
    }
}
