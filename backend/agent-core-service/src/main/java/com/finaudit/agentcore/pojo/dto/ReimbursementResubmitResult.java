package com.finaudit.agentcore.pojo.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 报销单修改重跑的结果上下文（报销域 → 工单状态机的编排契约，非对外 VO）。
 *
 * @param taskId      关联审核任务 ID（reimb.task_id，提交时反写；同单续跑据此定位工单）
 * @param inputParams 新任务快照入参（含新 items / 重算 claimedTotal / 附件引用，供步骤重规划投影）
 * @param totalAmount 服务端重算总额（Decimal，不信任前端）
 */
public record ReimbursementResubmitResult(Long taskId, Map<String, Object> inputParams, BigDecimal totalAmount) {
}
