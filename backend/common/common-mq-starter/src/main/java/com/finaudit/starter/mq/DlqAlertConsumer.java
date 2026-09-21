package com.finaudit.starter.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 死信队列告警消费者（P3.8 R7-8）。
 *
 * <p><b>它解决什么问题</b>：此前 {@code finaudit.dlq} <b>没有任何消费者</b>——
 * 消息进了 DLQ 就等于掉进黑洞：没有告警、没有指标，只能靠人工登管理台翻队列。
 * 而 DLQ 恰恰装着最需要被看见的消息：反序列化失败（DTO 契约不兼容）、
 * 消费重试耗尽（业务持续失败）的消息。症状表现是「消息发了但没人推进任务」，
 * 排查方向完全没有线索。</p>
 *
 * <p><b>⚠️ 已知取舍（诚实标注）</b>：本消费者会 <b>ACK 并移除</b> DLQ 中的消息（RabbitMQ 不支持"只查看不消费"）。
 * 因此它把完整信息（死亡原因头 + 原始 body）打进 ERROR 日志作为告警，
 * 但<b>日志不是持久化存储</b>（本仓日志只输出控制台，见 conventions §4）。
 * 生产环境应改用 Shovel/Federation 把 DLQ 转存到独立的死信库，
 * 或给本消费者接上告警通道（站内信/Webhook，已登记在 P4/B-8）。</p>
 *
 * <p><b>为什么不用 {@code @Component}</b>：本类位于 starter 包（{@code com.finaudit.starter.mq}），
 * 而各服务的组件扫描根是 {@code com.finaudit.<module>}——加 {@code @Component} 不会被扫到。
 * starter 的注册入口是 {@code @AutoConfiguration}，故由 {@code CommonMqAutoConfiguration} 显式声明为 Bean。</p>
 */
public class DlqAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqAlertConsumer.class);

    /** 日志中 body 的最大截断长度（避免超大报文刷爆日志） */
    private static final int MAX_BODY_LOG = 2000;

    @RabbitListener(queues = MqTopology.Q_DLQ)
    public void onDeadLetter(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        log.error("⛔ 死信消息告警：exchange={}, routingKey={}, death={}, body(截断 {} 字符)={}",
                message.getMessageProperties().getReceivedExchange(),
                message.getMessageProperties().getReceivedRoutingKey(),
                // x-death 记录了被谁拒绝、拒了几次、原队列——定位「哪条链路把消息丢掉」的关键
                headers.get("x-death"),
                MAX_BODY_LOG,
                bodyOf(message));
    }

    private static String bodyOf(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            return "(empty)";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > MAX_BODY_LOG ? text.substring(0, MAX_BODY_LOG) + "…" : text;
    }
}
