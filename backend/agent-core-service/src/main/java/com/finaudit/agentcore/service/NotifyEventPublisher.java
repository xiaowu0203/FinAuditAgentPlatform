package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.NotifyEvent;
import com.finaudit.agentcore.pojo.entity.NotifyMessage;
import com.finaudit.agentcore.pojo.entity.NotifyWebhook;
import com.finaudit.agentcore.support.NotifyEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知事件统一发布器（P3.8 R8-2）：一个业务事件 → 站内信 + Webhook 两条通道。
 *
 * <p><b>为什么收敛成一个入口</b>：同一事件若各通道分别拼装文案，迟早出现"站内信说已驳回、
 * Webhook 说审批通过"（口径漂移）。调用方只描述"发生了什么"（{@link NotifyEvent}），
 * 由本类负责分发与落库。</p>
 *
 * <p><b>三条纪律</b>：</p>
 * <ol>
 *   <li><b>与业务同事务</b>：本类方法不带 {@code REQUIRES_NEW}，即在调用方事务内写库。
 *       这样"业务提交成功 ⇒ 通知/投递记录必在"（outbox 语义）；若业务回滚，通知也一起回滚——
 *       不会出现"通知说已通过、其实事务回滚了"的假消息。</li>
 *   <li><b>任何失败都不上抛</b>：通知是辅助能力，绝不能因为写提醒失败把一次审批、一次收尾回滚掉。
 *       ⚠️ 已知边界：MySQL 下"单条语句失败"不会污染整个事务，但 PostgreSQL 会把事务标记为 aborted
 *       （25P02，与 R1 预算占用注释里记录的同一条结论相反方向：那边是"事务内 catch 后继续写库"，
 *       这里是"失败后不再写库"）。当前项目仅支持 MySQL，若将来换库需重新评估。</li>
 *   <li><b>平台自身故障不复用故障通道</b>：{@code WEBHOOK_DEAD} 事件只发站内信，不再生成 Webhook 投递，
 *       否则"Webhook 挂了 → 发告警 → 走同一个挂掉的 Webhook"会自我放大。</li>
 * </ol>
 */
@Service
public class NotifyEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotifyEventPublisher.class);

    private final NotifyMessageService messageService;
    private final NotifyWebhookService webhookService;
    private final WebhookDeliveryService deliveryService;

    public NotifyEventPublisher(NotifyMessageService messageService,
                                NotifyWebhookService webhookService,
                                WebhookDeliveryService deliveryService) {
        this.messageService = messageService;
        this.webhookService = webhookService;
        this.deliveryService = deliveryService;
    }

    /**
     * 发布一个事件。
     *
     * @param event 事件（租户、事件码、文案、收件人、负载）
     */
    public void publish(NotifyEvent event) {
        if (event == null || event.tenantId() == null || event.eventType() == null) {
            log.warn("忽略非法通知事件（缺租户或事件码）: {}", event);
            return;
        }
        sendMessages(event);
        enqueueWebhooks(event);
    }

    private void sendMessages(NotifyEvent event) {
        if (!event.hasRecipients()) {
            return;
        }
        try {
            List<NotifyMessage> messages = new ArrayList<>(event.recipients().size());
            for (Long userId : event.recipients()) {
                if (userId == null) {
                    continue;
                }
                // 幂等键按收件人展开：唯一索引是 (tenant_id, dedupe_key)，群发时不能所有人共用一个键
                String dedupeKey = event.dedupeKey() == null
                        ? null : event.dedupeKey() + ":" + userId;
                messages.add(NotifyMessage.from(event.tenantId(), userId, event.category(),
                        event.eventType(), event.title(), event.content(),
                        event.bizType(), event.bizId(), event.link(), dedupeKey));
            }
            int written = messageService.send(messages);
            log.debug("站内信已发布: event={}, 目标 {} 人, 实写 {} 条（去重/失败行不计）",
                    event.eventType(), event.recipients().size(), written);
        } catch (Exception e) {
            // 双重兜底：NotifyMessageService 内部已不抛，这里防的是文案组装等前置环节
            log.error("站内信发布失败（已忽略，不影响业务）: event={}, bizId={}, err={}",
                    event.eventType(), event.bizId(), e.toString());
        }
    }

    private void enqueueWebhooks(NotifyEvent event) {
        if (NotifyEventTypes.WEBHOOK_DEAD.equals(event.eventType())) {
            // 见类注释纪律 3：平台故障不复用故障通道
            return;
        }
        try {
            List<NotifyWebhook> targets = webhookService.findSubscribers(event.tenantId(), event.eventType());
            if (targets.isEmpty()) {
                return;
            }
            int rows = deliveryService.enqueue(event, targets);
            log.debug("Webhook 投递已登记: event={}, 目标 {} 个, 登记 {} 条",
                    event.eventType(), targets.size(), rows);
        } catch (Exception e) {
            log.error("Webhook 投递登记失败（已忽略，不影响业务）: event={}, bizId={}, err={}",
                    event.eventType(), event.bizId(), e.toString());
        }
    }
}
