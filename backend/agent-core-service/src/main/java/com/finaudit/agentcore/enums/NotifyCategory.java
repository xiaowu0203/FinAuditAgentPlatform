package com.finaudit.agentcore.enums;

/**
 * 站内信类别（P3.8 R8-2）。
 *
 * <p>只分两类，刻意不按业务域细分：{@code AUDIT} 是「与我有关的单据流转了」（申请人/审批人视角，
 * 收件人关心的是"我的单子"），{@code ALERT} 是「平台自身出问题了」（运维/管理员视角，
 * 收件人关心的是"系统是否健康"）。前端的未读红点、消息分组都按这两条线走。</p>
 */
public enum NotifyCategory {

    /** 审核流转：任务的推进、工单的审批结果等业务事件。 */
    AUDIT,

    /** 平台告警：MQ 死信、Webhook 投递失败等运维事件。 */
    ALERT
}
