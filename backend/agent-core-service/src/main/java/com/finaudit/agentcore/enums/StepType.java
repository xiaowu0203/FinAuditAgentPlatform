package com.finaudit.agentcore.enums;

/**
 * 步骤类型（P3.8 R6-5 声明式流水线引入）。
 *
 * <p>此前 {@code stepType} 是散落在各处的字符串字面量（{@code "TOOL"} / {@code "LLM"}），
 * 拼错不会报错、只会在分发时落进「未知类型 → 任务失败」分支。抽成枚举后，
 * 流水线声明、分发判断、自校验采集口径共用同一组常量。</p>
 */
public enum StepType {

    /** 工具调用步骤：发布 MQ 交 tool-service 执行，结果经 tool.result 回调推进 */
    TOOL,

    /** 大模型语义步骤：agent-core 进程内同步调用模型 */
    LLM
}
