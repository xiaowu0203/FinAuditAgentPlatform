package com.finaudit.starter.mq.message;

import java.util.Map;

/**
 * 工具执行结果消息（agent-core 消费：tool.result）。
 *
 * @param taskId      任务 ID
 * @param stepId      步骤 ID
 * @param tenantId    租户 ID
 * @param toolCode    工具编码
 * @param result      执行结果
 * @param success     是否成功
 * @param errorMsg    失败原因
 * @param costTimeMs  耗时（毫秒）
 */
public record ToolResultMessage(Long taskId, Long stepId, Long tenantId,
                                String toolCode, Map<String, Object> result,
                                boolean success, String errorMsg, Long costTimeMs) {
}
