package com.finaudit.starter.mq.config;

import com.finaudit.starter.mq.DlqAlertConsumer;
import com.finaudit.starter.mq.MqTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

/**
 * MQ 事件编排通用自动装配：JSON 消息转换器 + 任务交换机/队列/绑定声明 + 死信告警与发送端回执。
 * <p>agent-core 与 tool-service 均引入本 starter，交换机/队列声明幂等（重复声明配置一致无副作用）。
 * 失败消息经队列死信参数自动投递到 {@link MqTopology#Q_DLQ}，并由 {@link DlqAlertConsumer} 告警。</p>
 */
@AutoConfiguration
@ConditionalOnClass(MessageConverter.class)
public class CommonMqAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommonMqAutoConfiguration.class);

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

    /**
     * 死信队列告警消费者（P3.8 R7-8）。
     * <p>此前 DLQ 无任何消费者，消息进 DLQ 即等于掉进黑洞。见 {@link DlqAlertConsumer} 的取舍说明。</p>
     * <p>可用 {@code finaudit.mq.dlq-alert.enabled=false} 关闭（如需改用外部 Shovel/监控）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "finaudit.mq.dlq-alert", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DlqAlertConsumer dlqAlertConsumer() {
        return new DlqAlertConsumer();
    }

    /**
     * 发送端回执告警（P3.8 R7-8）：publisher confirm 未确认 / mandatory 不可路由时打 ERROR。
     *
     * <p>配合 yml 的 {@code publisher-confirm-type: correlated} 与 {@code publisher-returns: true} 生效。
     * 没有这两个回调时，「消息没送到」是完全静默的——只能靠下游任务卡住（等 30 分钟任务级超时）
     * 反推，属于最费时的一类故障。</p>
     */
    @Bean
    @ConditionalOnClass(RabbitTemplate.class)
    public RabbitTemplateCustomizer mqPublisherReceiptCustomizer() {
        return template -> {
            template.setMandatory(true);
            template.setConfirmCallback((correlationData, ack, cause) -> {
                if (!ack) {
                    log.error("MQ 消息未被 broker 确认（publisher confirm nack）: correlationId={}, cause={}",
                            correlationData == null ? null : correlationData.getId(), cause);
                }
            });
            template.setReturnsCallback(returned -> log.error(
                    "MQ 消息无法路由（mandatory return，交换机在但队列/绑定缺失）: exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
        };
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
