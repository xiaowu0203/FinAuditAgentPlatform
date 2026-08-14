package com.finaudit.starter.mq.config;

import com.finaudit.starter.mq.MqTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

/**
 * MQ 事件编排通用自动装配：JSON 消息转换器 + 任务交换机/队列/绑定声明。
 * <p>agent-core 与 tool-service 均引入本 starter，交换机/队列声明幂等（重复声明配置一致无副作用）。
 * 失败消息经队列死信参数自动投递到 {@link MqTopology#Q_DLQ}。</p>
 */
@AutoConfiguration
@ConditionalOnClass(MessageConverter.class)
public class CommonMqAutoConfiguration {

    /** JSON 消息转换器：跨服务反序列化需信任共享消息 DTO 的完整包名 */
    @Bean
    @ConditionalOnMissingBean
    public MessageConverter jacksonMessageConverter() {
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        // 注意：DefaultJackson2JavaTypeMapper 按「类所在完整包名」精确 equals 判定，
        // 前缀匹配无效，必须列出消息 DTO 所在包（com.finaudit.starter.mq.message）
        typeMapper.setTrustedPackages(MqTopology.MESSAGE_PACKAGE);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public DirectExchange finauditTaskExchange() {
        return new DirectExchange(MqTopology.EXCHANGE, true, false);
    }

    @Bean
    public Queue taskSubmitQueue() {
        return new Queue(MqTopology.Q_TASK_SUBMIT, true, false, false, dlqArgs());
    }

    @Bean
    public Queue toolExecuteQueue() {
        return new Queue(MqTopology.Q_TOOL_EXECUTE, true, false, false, dlqArgs());
    }

    @Bean
    public Queue toolResultQueue() {
        return new Queue(MqTopology.Q_TOOL_RESULT, true, false, false, dlqArgs());
    }

    @Bean
    public Queue taskDlq() {
        return new Queue(MqTopology.Q_DLQ, true);
    }

    @Bean
    public Binding taskSubmitBinding() {
        return BindingBuilder.bind(taskSubmitQueue())
                .to(finauditTaskExchange()).with(MqTopology.ROUTING_TASK_SUBMIT);
    }

    @Bean
    public Binding toolExecuteBinding() {
        return BindingBuilder.bind(toolExecuteQueue())
                .to(finauditTaskExchange()).with(MqTopology.ROUTING_TOOL_EXECUTE);
    }

    @Bean
    public Binding toolResultBinding() {
        return BindingBuilder.bind(toolResultQueue())
                .to(finauditTaskExchange()).with(MqTopology.ROUTING_TOOL_RESULT);
    }

    @Bean
    public Binding taskDlqBinding() {
        return BindingBuilder.bind(taskDlq())
                .to(finauditTaskExchange()).with(MqTopology.ROUTING_DLQ);
    }

    /** 队列死信参数：reject 消息 → 投递到任务交换机 routing key=dlq → DLQ */
    private Map<String, Object> dlqArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqTopology.EXCHANGE);
        args.put("x-dead-letter-routing-key", MqTopology.ROUTING_DLQ);
        return args;
    }
}
