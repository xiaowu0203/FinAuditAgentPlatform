package com.finaudit.starter.mq.config;

import com.finaudit.starter.mq.DlqAlertConsumer;
import com.finaudit.starter.mq.MqAlertSink;
import com.finaudit.starter.mq.MqTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.beans.factory.ObjectProvider;
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
     * 死信队列告警消费者（P3.8 R7-8；R8-2 接出 {@link MqAlertSink} 通知通道）。
     * <p>此前 DLQ 无任何消费者，消息进 DLQ 即等于掉进黑洞。见 {@link DlqAlertConsumer} 的取舍说明。</p>
     * <p>告警出口经 {@code ObjectProvider} 可选注入：业务侧提供 {@code MqAlertSink} Bean 即生效
     * （agent-core 实现为「管理员站内信 + MQ_DLQ_ALERT Webhook 事件」）；未提供时退化为只打日志。</p>
     * <p>⚠️ 必须绑定 {@link #dlqListenerContainerFactory}（不做 JSON 解析），原因见该 Bean 的注释。</p>
     * <p>可用 {@code finaudit.mq.dlq-alert.enabled=false} 关闭（改用外部 Shovel/监控，
     * 或在无告警通道的服务里避免与 agent-core 竞争消费 DLQ）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "finaudit.mq.dlq-alert", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DlqAlertConsumer dlqAlertConsumer(ObjectProvider<MqAlertSink> alertSinkProvider) {
        return new DlqAlertConsumer(alertSinkProvider);
    }

    /**
     * 死信告警专用监听容器工厂：**不做 JSON 反序列化**（P3.8 R8-2 运行时实测踩到）。
     *
     * <p><b>不修会怎样（实测证据）</b>：默认容器工厂用的是全服务共享的
     * {@link Jackson2JsonMessageConverter}。当死信消息的 body 不是合法 JSON 时
     * （正是「DTO 契约不兼容 / 上游发了坏报文」这类最该被看见的死信），
     * 容器会在<b>调用监听器方法之前</b>就抛 {@code MessageConversionException}——于是：</p>
     * <ul>
     *   <li>监听器里的 {@code try/catch} 完全够不着（异常发生在它之前），告警永远不会发出；</li>
     *   <li>配合 {@code listener.simple.default-requeue-rejected: false}，消息被 reject；
     *       而 DLQ 自身没有再配死信交换机 → <b>消息被静默丢弃</b>（RabbitMQ 侧表现为
     *       {@code deliver} 递增、{@code ack} 不动、ready/unacked 均为 0）；</li>
     *   <li>对照组：同样投到 DLQ 的<b>合法 JSON</b> 消息能被正常 ack 并产生告警 →
     *       即"告警通道只对不需要告警的消息有效"。</li>
     * </ul>
     *
     * <p>用 {@link SimpleMessageConverter}（按字节/字符串转换，不解析 JSON）后，
     * 任何报文都能原样送达监听器，最终由 {@code MqAlertSink} 落成站内信/告警事件。</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "dlqListenerContainerFactory")
    @ConditionalOnClass(SimpleRabbitListenerContainerFactoryConfigurer.class)
    public SimpleRabbitListenerContainerFactory dlqListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
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
