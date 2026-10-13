package com.finaudit.agentcore.mq;

import com.finaudit.agentcore.domain.NotifyEvent;
import com.finaudit.agentcore.enums.NotifyCategory;
import com.finaudit.agentcore.service.NotifyEventPublisher;
import com.finaudit.agentcore.service.NotifyRecipientService;
import com.finaudit.agentcore.support.NotifyEventTypes;
import com.finaudit.starter.mq.MqAlert;
import com.finaudit.starter.mq.MqAlertSink;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信告警的通知出口实现（P3.8 R8-2，收口 R7-8 登记的 B-8 遗留项）。
 *
 * <p><b>改造前</b>：DLQ 消息只打成 ERROR 日志，而本仓日志只输出到 IDE 控制台、不落盘——
 * 等于"告警发给了没人看的地方"。</p>
 *
 * <p><b>改造后</b>：同一条死信 → ①给管理员写站内信（可查、可读、不会丢）；
 * ②触发 {@code MQ_DLQ_ALERT} Webhook 事件（可对接外部告警系统）。</p>
 *
 * <p><b>幂等</b>：站内信带 {@code dedupe_key = MQ_DLQ_ALERT:<routingKey>:<body 摘要>}。
 * 同一条死信若因故被重复投递（消费者 ACK 前重启），管理员不会收到重复提醒。</p>
 *
 * <p><b>租户</b>：DLQ 消息本身<b>不带租户信息</b>（body 已可能是反序列化失败的原始字节，
 * 取不出 tenantId）。故告警发给<b>默认租户</b>的管理员——这是刻意的保守选择：
 * 与其猜一个租户（猜错等于把 A 租户的报文推给 B 租户的管理员），不如只在平台管理员处汇总。</p>
 */
@Component
public class MqAlertNotifySink implements MqAlertSink {

    private static final Logger log = LoggerFactory.getLogger(MqAlertNotifySink.class);

    /** 告警正文里保留的 body 长度（与 starter 侧日志截断一致，避免站内信被长报文刷屏） */
    private static final int CONTENT_BODY_MAX = 500;

    private final NotifyEventPublisher publisher;
    private final NotifyRecipientService recipientService;

    public MqAlertNotifySink(NotifyEventPublisher publisher, NotifyRecipientService recipientService) {
        this.publisher = publisher;
        this.recipientService = recipientService;
    }

    @Override
    public void onDeadLetter(MqAlert alert) {
        if (alert == null) {
            return;
        }
        Long tenantId = TenantContextHolder.DEFAULT_TENANT_ID;
        // ⚠️ 整段都在默认租户上下文内执行，而不只是"查管理员"那一步：
        //    多租户拦截器会给 SELECT 自动加 tenant_id 条件（如"本租户有哪些 Webhook 订阅了该事件"），
        //    上下文缺失时它会回退默认租户——对租户 2 的告警就会去查租户 1 的配置。
        //    写入侧（notify_message/notify_delivery）虽显式带了 tenant_id 不受影响，但读侧必须一致。
        TenantContextHolder.runWith(tenantId, () -> {
            List<Long> admins;
            try {
                admins = recipientService.administrators(tenantId);
            } catch (Exception e) {
                log.warn("DLQ 告警解析管理员失败（已降级为不通知，仅落日志）: {}", e.toString());
                admins = List.of();
            }

            String title = "MQ 死信告警：" + alert.routingKey();
            String content = "队列 " + alert.routingKey() + " 收到死信消息（原交换机 " + alert.exchange()
                    + "）。死亡原因：" + alert.deathReason()
                    + "；报文" + (alert.truncated() ? "已截断，前 " : "共 ") + CONTENT_BODY_MAX + " 字符："
                    + truncate(alert.body());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exchange", alert.exchange());
            data.put("routingKey", alert.routingKey());
            data.put("deathReason", alert.deathReason());
            data.put("bodyBytes", alert.bodyBytes());
            data.put("bodyTruncated", alert.truncated());

            NotifyEvent event = new NotifyEvent(tenantId, NotifyEventTypes.MQ_DLQ_ALERT, NotifyCategory.ALERT,
                    title, content, "MQ", null, null, admins,
                    NotifyEventTypes.MQ_DLQ_ALERT + ":" + alert.routingKey() + ":" + digest(alert), data);
            publisher.publish(event);
        });
    }

    /** 幂等摘要：用 body 的哈希前缀（同一死信重复投递时 body 相同）。 */
    private static String digest(MqAlert alert) {
        String body = alert.body() == null ? "" : alert.body();
        return Integer.toHexString(body.hashCode());
    }

    private static String truncate(String body) {
        if (body == null) {
            return "(empty)";
        }
        return body.length() <= CONTENT_BODY_MAX ? body : body.substring(0, CONTENT_BODY_MAX) + "…";
    }
}
