package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.NotifyEvent;
import com.finaudit.agentcore.enums.AuditAction;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AuditTicket;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务通知门面单测（P3.8 R8-2）。
 *
 * <p>门面是"业务语义 → 事件码/收件人/文案"的唯一映射点，映射错了不会报错、只会让人收到错的通知，
 * 故把每个动作的映射与两类收件人（申请人/审批人）都钉住。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyFacadeTest {

    @Mock
    private NotifyEventPublisher publisher;
    @Mock
    private NotifyRecipientService recipientService;

    @InjectMocks
    private NotifyFacade facade;

    private static AuditTicket ticket() {
        AuditTicket t = new AuditTicket();
        t.setId(12L);
        t.setTenantId(1L);
        t.setTicketNo("AT-T1");
        t.setCreatedBy(7L);
        t.setTriggerType("RULE_FAIL");
        t.setRiskDesc("RULE_FAIL:规则校验超标");
        return t;
    }

    private static AgentTask task() {
        AgentTask t = new AgentTask();
        t.setId(100L);
        t.setTenantId(1L);
        t.setTaskNo("T202601010000000001");
        t.setCreatedBy(7L);
        t.setInputParams(Map.of("reimbId", 5));
        return t;
    }

    private NotifyEvent capture() {
        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void autoPassedNotifiesApplicantWithReimbursementLink() {
        when(recipientService.applicant(any())).thenReturn(List.of(7L));

        facade.autoPassed(task());

        NotifyEvent event = capture();
        assertEquals(NotifyEventTypes.TASK_AUTO_PASSED, event.eventType());
        assertEquals(List.of(7L), event.recipients());
        assertEquals("/reimbursements/5", event.link(), "应跳到提交人自己的报销单详情");
        assertEquals("TASK", event.bizType());
    }

    @Test
    void needReviewNotifiesBothApplicantAndApprovers() {
        when(recipientService.applicant(any())).thenReturn(List.of(7L));
        when(recipientService.approvers(1L)).thenReturn(List.of(1L, 2L));

        facade.needReview(task(), ticket(), false);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
        List<NotifyEvent> events = captor.getAllValues();
        assertEquals(NotifyEventTypes.TASK_NEED_REVIEW, events.get(0).eventType());
        assertEquals(List.of(7L), events.get(0).recipients(), "申请人视角");
        assertEquals(NotifyEventTypes.TICKET_CREATED, events.get(1).eventType());
        assertEquals(List.of(1L, 2L), events.get(1).recipients(), "审批人视角（按权限码反查）");
        assertEquals("/audits/12", events.get(1).link());
    }

    @Test
    void rerunNoticeUsesDistinctTitle() {
        when(recipientService.applicant(any())).thenReturn(List.of(7L));
        when(recipientService.approvers(1L)).thenReturn(List.of());

        facade.needReview(task(), ticket(), true);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
        assertTrue(captor.getAllValues().get(0).title().contains("修改后仍未通过"),
                "重跑后再次命中要有不同文案，实际=" + captor.getAllValues().get(0).title());
        assertEquals(true, captor.getAllValues().get(0).data().get("rerun"));
    }

    @Test
    void ticketActionsMapToExpectedEventCodes() {
        when(recipientService.applicant(any())).thenReturn(List.of(7L));

        facade.ticketAction(ticket(), AuditAction.APPROVE, "同意");
        facade.ticketAction(ticket(), AuditAction.REJECT, "金额超标");
        facade.ticketAction(ticket(), AuditAction.TERMINATE, null);
        facade.ticketAction(ticket(), AuditAction.WITHDRAW_REFUSE, null);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(publisher, org.mockito.Mockito.times(4)).publish(captor.capture());
        List<NotifyEvent> events = captor.getAllValues();

        assertEquals(NotifyEventTypes.TICKET_APPROVED, events.get(0).eventType());
        assertEquals(NotifyEventTypes.TICKET_REJECTED, events.get(1).eventType());
        assertEquals(NotifyEventTypes.TICKET_TERMINATED, events.get(2).eventType());
        assertEquals(NotifyEventTypes.TICKET_WITHDRAW_REFUSED, events.get(3).eventType());
        // 五个动作的收件人都是申请人（"我的单子被怎么处理了"）
        for (NotifyEvent event : events) {
            assertEquals(List.of(7L), event.recipients());
        }
        assertTrue(events.get(1).content().contains("金额超标"), "驳回必须带上审批意见（用户才知道改什么）");
    }

    @Test
    void nonOutcomeActionsDoNotNotify() {
        facade.ticketAction(ticket(), AuditAction.RERUN, null);
        facade.ticketAction(ticket(), AuditAction.AMEND, null);
        facade.ticketAction(ticket(), AuditAction.WITHDRAW_REQ, null);

        verify(publisher, never()).publish(any());
    }

    @Test
    void withdrawRequestedNotifiesApproversOnly() {
        when(recipientService.approvers(1L)).thenReturn(List.of(1L));

        facade.withdrawRequested(ticket());

        NotifyEvent event = capture();
        assertEquals(NotifyEventTypes.TICKET_WITHDRAW_REQUESTED, event.eventType());
        assertEquals(List.of(1L), event.recipients());
        assertEquals("/audits/12", event.link());
    }

    @Test
    void failedNotifiesApplicantWithAbbreviatedError() {
        when(recipientService.applicant(any())).thenReturn(List.of(7L));
        StringBuilder longError = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longError.append('e');
        }

        facade.failed(task(), longError.toString());

        NotifyEvent event = capture();
        assertEquals(NotifyEventTypes.TASK_FAILED, event.eventType());
        assertTrue(event.content().length() < 400, "超长错误必须缩写，否则正文被截断得莫名其妙");
    }

    @Test
    void nullInputsAreTolerated() {
        facade.autoPassed(null);
        facade.needReview(null, null, false);
        facade.failed(null, "x");
        facade.withdrawRequested(null);
        facade.ticketAction(null, AuditAction.APPROVE, null);
        facade.ticketAction(ticket(), null, null);

        verify(publisher, never()).publish(any());
    }
}
