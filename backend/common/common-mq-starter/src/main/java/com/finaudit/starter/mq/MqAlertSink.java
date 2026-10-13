package com.finaudit.starter.mq;

/**
 * 死信告警出口（SPI，P3.8 R8-2）。
 *
 * <p><b>它解决什么问题</b>：{@link DlqAlertConsumer} 此前只能把死信打成 ERROR 日志——而本仓日志
 * 只输出到 IDE 控制台、不落盘（见 {@code docs/architecture/conventions.md} §4），
 * 等于"告警发给了没人看的地方"。R7-8 明确把「接告警通道」登记为 B-8 的收口项。</p>
 *
 * <p><b>为什么是 SPI 而不是直接在 starter 里落库</b>：{@code common-mq-starter} 是纯能力 Starter
 * （不依赖 MyBatis/数据源，见 {@code common-mq-starter/README.md}）。告警落到哪是业务决定：
 * 谁关心告警谁实现本接口（当前 agent-core 实现为「写管理员站内信 + 触发 Webhook 事件」）。
 * 没有实现时 starter 行为与改造前完全一致（只打日志），不强迫每个服务引入通知表。</p>
 *
 * <p>与 R9-1 的 {@code ModelCallRecorder} 是同一套 SPI 模式：starter 定义接口，业务侧实现并注册为 Bean，
 * 由 starter 经 {@code ObjectProvider} 可选注入。</p>
 *
 * <p><b>实现约定</b>：实现方<b>不得抛异常</b>——告警通道故障不能反过来打断 DLQ 消费、
 * 更不能让消息重新入队形成告警风暴。starter 侧虽已 try/catch 兜底，但实现内部也应自行降级。</p>
 */
public interface MqAlertSink {

    /**
     * 收到一条死信消息。
     *
     * @param alert 死信载荷（body 已截断，见 {@link MqAlert#bodyBytes()}）
     */
    void onDeadLetter(MqAlert alert);
}
