package com.finaudit.agentcore.support;

import java.util.List;

/**
 * 通知事件类型目录（P3.8 R8-2）。
 *
 * <p><b>为什么用常量类而不是枚举</b>：事件类型是**对外契约**的一部分——它既写进
 * {@code notify_message.event_type}，也是 Webhook 订阅数组（{@code notify_webhook.event_types}）里
 * 接收方要填的值。接收方（第三方系统）拿到的是字符串，若哪天需要允许租户自定义事件码，
 * 枚举反而要先拆掉。故用常量 + {@link #ALL} 做校验。</p>
 *
 * <p>命名规范：{@code <业务域>_<发生了什么>}，全部大写下划线。</p>
 */
public final class NotifyEventTypes {

    private NotifyEventTypes() {
    }

    // ---------- 审核流转（收件人：申请人 / 审批人） ----------

    /** 流水线判定需人工复核，任务进入待审批（收件人：申请人）。 */
    public static final String TASK_NEED_REVIEW = "TASK_NEED_REVIEW";

    /** 自动化通过（AUTO_PASS，收件人：申请人）。 */
    public static final String TASK_AUTO_PASSED = "TASK_AUTO_PASSED";

    /** 流水线失败（收件人：申请人）。 */
    public static final String TASK_FAILED = "TASK_FAILED";

    /** 新建审批工单待处理（收件人：有 {@code audit:approve} 权限的人）。 */
    public static final String TICKET_CREATED = "TICKET_CREATED";

    /** 工单审批通过（收件人：申请人）。 */
    public static final String TICKET_APPROVED = "TICKET_APPROVED";

    /** 工单驳回（收件人：申请人，含结构化问题项摘要）。 */
    public static final String TICKET_REJECTED = "TICKET_REJECTED";

    /**
     * 单据作废（终止 / 撤销已同意；收件人：申请人）。
     *
     * <p>终止与撤销同意在语义上都是"这张单子到此为止"，故共用一个事件码，
     * 区别体现在标题文案（{@code title}）里；给 Webhook 消费方的机器可读区分见 {@code data.action}。</p>
     */
    public static final String TICKET_TERMINATED = "TICKET_TERMINATED";

    /** 撤销申请被拒绝（单据保持有效；收件人：申请人）。 */
    public static final String TICKET_WITHDRAW_REFUSED = "TICKET_WITHDRAW_REFUSED";

    /** 申请人请求撤销，待财务处理（收件人：有 {@code audit:approve} 权限的人）。 */
    public static final String TICKET_WITHDRAW_REQUESTED = "TICKET_WITHDRAW_REQUESTED";

    // ---------- 平台告警（收件人：管理员） ----------

    /** MQ 死信告警（R7-8 收口：DLQ 从"只打日志"变成可投递的告警）。 */
    public static final String MQ_DLQ_ALERT = "MQ_DLQ_ALERT";

    /**
     * Webhook 投递连续失败（收件人：Webhook 配置创建人）。
     *
     * <p>⚠️ 本事件<b>刻意不产生 Webhook 投递</b>：否则"Webhook 挂了 → 告警 → 走同一个挂掉的 Webhook → 又挂"
     * 会形成自我放大的失败循环（见 {@code NotifyEventPublisher} 的落点过滤）。</p>
     */
    public static final String WEBHOOK_DEAD = "WEBHOOK_DEAD";

    /**
     * 连通性自测事件（由 {@code POST /api/v1/notify/webhooks/{id}/test} 触发）。
     *
     * <p>让管理员在配置完地址后立刻验证"能不能通、签名对不对"，而不必等一个真实业务事件。
     * 它不参与订阅匹配（测试投递直接指定目标配置）。</p>
     */
    public static final String WEBHOOK_TEST = "WEBHOOK_TEST";

    /** 全部事件类型（Webhook 订阅校验用）。 */
    public static final List<String> ALL = List.of(
            TASK_NEED_REVIEW, TASK_AUTO_PASSED, TASK_FAILED,
            TICKET_CREATED, TICKET_APPROVED, TICKET_REJECTED, TICKET_TERMINATED,
            TICKET_WITHDRAW_REQUESTED, TICKET_WITHDRAW_REFUSED,
            MQ_DLQ_ALERT, WEBHOOK_DEAD, WEBHOOK_TEST);

    /** 事件码是否合法（Webhook 订阅数组校验）。 */
    public static boolean isValid(String eventType) {
        return eventType != null && ALL.contains(eventType);
    }
}