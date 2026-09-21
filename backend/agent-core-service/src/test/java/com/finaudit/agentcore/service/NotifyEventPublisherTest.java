package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.NotifyEvent;
import com.finaudit.agentcore.enums.NotifyCategory;
import com.finaudit.agentcore.pojo.entity.NotifyMessage;
import com.finaudit.agentcore.pojo.entity.NotifyWebhook;
import com.finaudit.agentcore.support.NotifyEventTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知发布器单测（P3.8 R8-2）。
 *
 * <p>钉住三件事：① 站内信按收件人展开、幂等键带收件人后缀（否则群发时唯一索引会互相顶掉，
 * 只有第一个收件人能收到）；② {@code WEBHOOK_DEAD} 不再产生投递（防"告警走挂掉的通道"自我放大）；
 * ③ 任一通道抛异常都不得上抛（通知失败不能回滚业务）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyEventPublisherTest {

    @Mock
    private NotifyMessageService messageService;
    @Mock
    private NotifyWebhookService webhookService;
    @Mock
    private WebhookDeliveryService deliveryService;

    @InjectMocks
    private NotifyEventPublisher publisher;

    private static NotifyEvent event(List<Long> recipients, String dedupeKey) {
        return new NotifyEvent(1L, NotifyEventTypes.TICKET_CREATED, NotifyCategory.AUDIT,
                "新审批工单待处理", "内容", "TICKET", 12L, "/audits/12", recipients, dedupeKey,
                Map.of("ticketNo", "AT-1"));
    }

    @Test
    void expandsMessagesPerRecipientWithPerUserDedupeSuffix() {
        publisher.publish(event(List.of(1L, 2L, 3L), "TICKET_CREATED:12"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotifyMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageService).send(captor.capture());
        List<NotifyMessage> messages = captor.getValue();

        assertEquals(3, messages.size(), "群发必须按收件人展开");
        assertEquals(List.of(1L, 2L, 3L), messages.stream().map(NotifyMessage::getUserId).toList());
        assertEquals("TICKET_CREATED:12:1", messages.get(0).getDedupeKey(),
                "幂等键必须拼上收件人：唯一索引是 (tenant_id, dedupe_key)，共用同一个键会让其余收件人被去重掉");
        assertEquals("/audits/12", messages.get(0).getLink());
        assertEquals("TICKET", messages.get(0).getBizType());
    }

    @Test
    void noRecipientsMeansNoSiteMessageButWebhookStillFires() {
        when(webhookService.findSubscribers(1L, NotifyEventTypes.TICKET_CREATED))
                .thenReturn(List.of(new NotifyWebhook()));

        publisher.publish(event(List.of(), null));

        verify(messageService, never()).send(any());
        verify(deliveryService).enqueue(any(), any());
    }

    @Test
    void webhookDeadEventNeverCreatesDeliveries() {
        publisher.publish(new NotifyEvent(1L, NotifyEventTypes.WEBHOOK_DEAD, NotifyCategory.ALERT,
                "Webhook 投递连续失败", "内容", "WEBHOOK", 9L, null, List.of(7L), "k", Map.of()));

        verify(webhookService, never()).findSubscribers(anyLong(), anyString());
        verify(deliveryService, never()).enqueue(any(), any());
        verify(messageService).send(any());
    }

    @Test
    void subscriberLookupAndEnqueueAreSkippedWhenNoSubscribers() {
        when(webhookService.findSubscribers(1L, NotifyEventTypes.TICKET_CREATED)).thenReturn(List.of());

        publisher.publish(event(List.of(1L), null));

        verify(deliveryService, never()).enqueue(any(), any());
    }

    @Test
    void failuresAreSwallowedSoBusinessTransactionSurvives() {
        when(messageService.send(any())).thenThrow(new RuntimeException("db down"));
        when(webhookService.findSubscribers(eq(1L), anyString())).thenThrow(new RuntimeException("boom"));

        publisher.publish(event(List.of(1L), null));

        // 到这里没抛异常即通过：通知是辅助能力，不能把审批/收尾事务带崩
        verify(messageService, times(1)).send(any());
    }

    @Test
    void illegalEventIsIgnoredSilently() {
        publisher.publish(new NotifyEvent(null, null, null, null, null, null, null, null, null, null, null));

        verify(messageService, never()).send(any());
        verify(deliveryService, never()).enqueue(any(), any());
        assertTrue(true, "缺租户/事件码的事件应被忽略而不是 NPE");
    }
}
