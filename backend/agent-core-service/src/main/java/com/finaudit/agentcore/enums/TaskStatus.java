package com.finaudit.agentcore.enums;

/**
 * 任务状态机：PENDING → RUNNING → SUCCESS / FAILED（预留 MANUAL_REVIEW）。
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
    /** 人工复核（预留） */
    MANUAL_REVIEW
}
