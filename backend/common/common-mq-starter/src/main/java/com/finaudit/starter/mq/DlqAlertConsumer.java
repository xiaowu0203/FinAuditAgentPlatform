package com.finaudit.starter.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 死信队列告警消费者（P3.8 R7-8，R8-2 接出通知通道）。
 *
 * <p><b>它解决什么问题</b>：此前 {@code finaudit.dlq} <b>没有任何消费者</b>——
 * 消息进了 DLQ 就等于掉进黑洞：没有告警、没有指标，只能靠人工登管理台翻队列。
 * 而 DLQ 恰恰装着最需要被看见的消息：反序列化失败（DTO 契约不兼容）、
 * 消费重试耗尽（业务持续失败）的消息。症状表现是「消息发了但没人推进任务」，
 * 排查方向完全没有线索。</p>
 *
 * <p><b>R8-2 补上的最后一环</b>：本消费者仍把完整信息打进 ERROR 日志，但同时经
 * {@link MqAlertSink} SPI 把告警交给业务侧处置（agent-core 实现为「写管理员站内信 +
 * 触发 MQ_DLQ_ALERT Webhook 事件」）。日志不是持久化存储，站内信与投递台账是。</p>
 *
 * <p><b>⚠️ 已知取舍</b>：本消费者会 <b>ACK 并移除</b> DLQ 中的消息（RabbitMQ 不支持"只查看不消费"）。
 * 因此投递台账里留下的记录才是可追溯的那一份；生产环境若要求原始报文长期留存，
 * 应改用 Shovel/Federation 把 DLQ 转存到独立的死信库。</p>
 *
 * <p><b>⚠️ 竞争消费者</b>：agent-core 与 tool-service 都引入本 starter，都会声明本消费者，
 * 两个实例对 {@code finaudit.dlq} 形成竞争消费——告警会随机落在其中一边。因此
 * <b>只应在持有告警通道（实现了 {@link MqAlertSink}）的服务里保留本消费者</b>，
 * 其余服务用 {@code finaudit.mq.dlq-alert.enabled=false} 关闭（tool-service 已按此配置）。
 * 详见 {@code docs/api/notify.md}。</p>
 *
 * <p><b>为什么不用 {@code @Component}</b>：本类位于 starter 包（{@code com.finaudit.starter.mq}），
 * 而各服务的组件扫描根是 {@code com.finaudit.<module>}——加 {@code @Component} 不会被扫到。
 * starter 的注册入口是 {@code @AutoConfiguration}，故由 {@code CommonMqAutoConfiguration} 显式声明为 Bean。</p>
 */
public class DlqAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqAlertConsumer.class);

    /** 日志中 body 的最大截断长度（避免超大报文刷爆日志） */
    private static final int MAX_BODY_LOG = 2000;

    /** 告警出口（SPI）：可能没有实现（未实现时退化为只打日志，与改造前行为一致） */
    private final ObjectProvider<MqAlertSink> alertSinkProvider;

    public DlqAlertConsumer(ObjectProvider<MqAlertSink> alertSinkProvider) {
        this.alertSinkProvider = alertSinkProvider;
    }

    @RabbitListener(queues = MqTopology.Q_DLQ, containerFactory = "dlqListenerContainerFactory")
    public void onDeadLetter(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        String exchange = message.getMessageProperties().getReceivedExchange();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        // x-death 记录了被谁拒绝、拒了几次、原队列——定位「哪条链路把消息丢掉」的关键
        String death = String.valueOf(headers.get("x-death"));
        String body = bodyOf(message);
        int bodyBytes = message.getBody() == null ? 0 : message.getBody().length;

        log.error("⛔ 死信消息告警：exchange={}, routingKey={}, death={}, body(截断 {} 字符)={}",
                exchange, routingKey, death, MAX_BODY_LOG, body);

        dispatch(new MqAlert(exchange, routingKey, death, body, bodyBytes));
    }

    /**
     * 转交告警出口。
     *
     * <p><b>⚠️ 必须 catch Throwable 而不是 Exception</b>（P3.8 R8-2 实测教训）：只兜 Exception 时，
     * 出口若抛 {@code Error}（类/接口不匹配导致的 {@code NoSuchMethodError}、{@code NoClassDefFoundError} 等）
     * 会一路穿透到监听器容器——配合本仓的 {@code listener.simple.retry} + {@code default-requeue-rejected: false}，
     * 消息重试耗尽后被 <b>reject 丢弃</b>（DLQ 自己没有死信交换机），结果是
     * 「死信消失了、告警也没产生、只剩一行日志」，排查方向完全没有线索。
     * 实测证据：{@code finaudit.dlq} 的 {@code deliver} 递增而 {@code ack} 不动、且未写站内信。</p>
     *
     * <p>吞掉 Throwable 的安全性论证：本方法只做"通知/告警"这一辅助动作，
     * 吞掉它不会影响任何业务正确性；而放它穿透会连带丢掉原始死信报文（不可逆）。
     * 日志里带完整堆栈（末参数传 {@code t}），保证下一次异常可定位。</p>
     */
    private void dispatch(MqAlert alert) {
        if (alertSinkProvider == null) {
            return;
        }
        try {
            MqAlertSink sink = alertSinkProvider.getIfAvailable();
            if (sink == null) {
                log.debug("未提供 MqAlertSink 实现，死信告警仅落日志（service={}）", alert.routingKey());
                return;
            }
            sink.onDeadLetter(alert);
        } catch (Throwable t) {
            log.error("死信告警出口执行失败（已忽略，不影响 DLQ 消费；以下为完整原因）", t);
        }
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
