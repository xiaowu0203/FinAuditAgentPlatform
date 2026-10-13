package com.finaudit.agentcore.enums;

/**
 * Webhook 投递状态（P3.8 R8-2）。
 *
 * <p>刻意只有三态（无 {@code FAILED} 中间态）：一次失败只意味着"还没成功"，
 * {@code attempt_count} 与 {@code next_retry_at} 已足够表达"会不会再发"；
 * 多一个状态只会让运维需要推理"FAILED 到底还会不会重试"。</p>
 */
public enum WebhookDeliveryStatus {

    /** 待投递 / 待重试（尚未用尽次数）。 */
    PENDING,

    /** 投递成功（HTTP 2xx）。 */
    SUCCESS,

    /** 次数用尽，已放弃——**这是唯一需要告警的状态**。 */
    DEAD
}
