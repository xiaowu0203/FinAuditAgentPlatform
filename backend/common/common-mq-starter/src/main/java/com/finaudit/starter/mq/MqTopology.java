package com.finaudit.starter.mq;

/**
 * 事件驱动编排的 MQ 拓扑常量（RabbitMQ direct）。
 * <p>交换机 {@code finaudit.task.exchange}；三队列 + DLQ。所有消息 JSON 序列化。</p>
 */
public final class MqTopology {

    private MqTopology() {
    }

    /** 任务交换机（direct） */
    public static final String EXCHANGE = "finaudit.task.exchange";

    /** 队列 */
    public static final String Q_TASK_SUBMIT = "finaudit.task.submit.q";
    public static final String Q_TOOL_EXECUTE = "finaudit.tool.execute.q";
    public static final String Q_TOOL_RESULT = "finaudit.tool.result.q";
    public static final String Q_DLQ = "finaudit.dlq";

    /** routing key */
    public static final String ROUTING_TASK_SUBMIT = "task.submit";
    public static final String ROUTING_TOOL_EXECUTE = "tool.execute";
    public static final String ROUTING_TOOL_RESULT = "tool.result";
    public static final String ROUTING_DLQ = "dlq";

    /** 共享消息 DTO 所在包（Jackson 反序列化白名单，须精确到完整包名） */
    public static final String MESSAGE_PACKAGE = "com.finaudit.starter.mq.message";
}
