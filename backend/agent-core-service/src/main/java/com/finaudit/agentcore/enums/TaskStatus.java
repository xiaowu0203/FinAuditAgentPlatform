package com.finaudit.agentcore.enums;

/**
 * 任务状态机（P3a 起）：
 * <pre>
 * PENDING → RUNNING → SUCCESS
 *              ↑       ↘ FAILED
 *              │       ↘ APPROVAL_PENDING（流水线命中触发条件/LLM 非通过 → 待人工审批，P3b 审批动作流转）
 *              │
 *              └── APPROVAL_PENDING → REJECTED（人工驳回；仅枚举，P3b 工单动作使用）
 * </pre>
 * REJECTED 区别于系统 FAILED（人工驳回 vs 流程失败）。
 */
public enum TaskStatus {
    /** 已提交待处理 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 成功 */
    SUCCESS,
    /** 失败 */
    FAILED,
    /** 待审批（P3a 结果分支 NEED_REVIEW 暂停；P3b 工单通过→SUCCESS、驳回→REJECTED） */
    APPROVAL_PENDING,
    /** 人工驳回（P3b 审批动作，区别于系统 FAILED） */
    REJECTED
}
