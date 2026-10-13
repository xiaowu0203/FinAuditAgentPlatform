package com.finaudit.starter.mq;

/**
 * 死信告警载荷（P3.8 R8-2）。
 *
 * @param exchange    消息来源交换机（{@code null} 表示经默认交换机）
 * @param routingKey  消息来源 routing key
 * @param deathReason {@code x-death} 头的原文（记录了被谁拒绝、拒了几次、原队列——
 *                    定位「哪条链路把消息丢掉」的关键证据）
 * @param body        消息体（**已截断**，见 {@code bodyBytes} 判断是否截断）
 * @param bodyBytes   消息体原始字节数（截断前的长度）
 */
public record MqAlert(String exchange, String routingKey, String deathReason, String body, int bodyBytes) {

    /** 是否发生了截断（告警消费方据此提示"完整报文需查 broker/日志"）。 */
    public boolean truncated() {
        return bodyBytes > (body == null ? 0 : body.length());
    }

    /** 一句话摘要，便于作为通知标题。 */
    public String summary() {
        return "死信消息: " + routingKey + "（" + bodyBytes + " 字节）";
    }
}
