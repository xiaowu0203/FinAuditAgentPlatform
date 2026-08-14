package com.finaudit.agentcore.enums;

/**
 * 步骤状态机：PENDING → RUNNING → SUCCESS / FAILED（FAILED 且重试未达上限可转 RUNNING）。
 */
public enum StepStatus {
    /** 已提交待处理 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 成功 */
    SUCCESS,
    /** 失败 */
    FAILED
}
