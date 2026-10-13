package com.finaudit.agentcore.domain;

import com.finaudit.agentcore.enums.NotifyCategory;

import java.util.List;
import java.util.Map;

/**
 * 一条待发布的通知事件（P3.8 R8-2）。
 *
 * <p><b>为什么要有这层"事件对象"</b>：同一个业务事件要同时落到两个通道——站内信（给具体的人）
 * 与 Webhook（给租户配置的下游系统）。若各处分别拼装，标题/正文/负载迟早不一致
 * （典型症状：站内信说"已驳回"，Webhook 却说"审批通过"）。统一构造后，
 * {@code NotifyEventPublisher} 负责按通道分发，业务侧只描述"发生了什么"。</p>
 *
 * @param tenantId     租户ID
 * @param eventType    事件类型（见 {@link com.finaudit.agentcore.support.NotifyEventTypes}）
 * @param category     站内信类别
 * @param title        标题（站内信与 Webhook 共用）
 * @param content      正文（站内信展示；Webhook 负载中同名字段）
 * @param bizType      业务类型: TASK / TICKET / REIMBURSEMENT / MQ
 * @param bizId        业务ID
 * @param link         前端跳转路径（可空）
 * @param recipients   站内信收件人用户ID（可空/空列表 = 只发 Webhook）
 * @param dedupeKey    站内信幂等键（可空；群发时会自动拼接收件人后缀）
 * @param data         Webhook 负载的附加业务字段（可空；站内信不使用）
 */
public record NotifyEvent(Long tenantId,
                          String eventType,
                          NotifyCategory category,
                          String title,
                          String content,
                          String bizType,
                          Long bizId,
                          String link,
                          List<Long> recipients,
                          String dedupeKey,
                          Map<String, Object> data) {

    /** 是否需要发站内信。 */
    public boolean hasRecipients() {
        return recipients != null && !recipients.isEmpty();
    }
}
