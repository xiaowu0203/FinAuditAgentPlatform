package com.finaudit.starter.mq.message;

import java.util.Map;

/**
 * 工具执行消息（tool-service 消费：tool.execute）。
 *
 * @param taskId      任务 ID
 * @param stepId      步骤 ID
 * @param tenantId    租户 ID
 * @param toolCode    工具编码
 * @param inputParams 工具入参
 */
public record ToolExecuteMessage(Long taskId, Long stepId, Long tenantId,
                                 String toolCode, Map<String, Object> inputParams) {
}
