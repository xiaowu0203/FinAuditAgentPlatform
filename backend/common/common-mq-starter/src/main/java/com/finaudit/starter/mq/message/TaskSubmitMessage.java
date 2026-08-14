package com.finaudit.starter.mq.message;

/**
 * 任务提交消息（agent-core 消费：task.submit）。
 *
 * @param taskId   任务 ID
 * @param tenantId 租户 ID
 */
public record TaskSubmitMessage(Long taskId, Long tenantId) {
}
