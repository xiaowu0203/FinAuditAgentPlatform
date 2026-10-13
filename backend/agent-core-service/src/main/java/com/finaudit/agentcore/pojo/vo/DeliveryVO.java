package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.NotifyDelivery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Webhook 投递台账出参（P3.8 R8-2）。
 *
 * <p>刻意**不返回 payload 全文**：那是事件原始报文（可能含单据金额、商户名等业务数据），
 * 而运维看台账是为了判断"投没投成功、为什么失败"。需要报文时按 {@code eventId} 去接收方或日志对照。</p>
 */
@Getter
@Setter
public class DeliveryVO {

    @Schema(description = "投递记录ID")
    private Long id;

    @Schema(description = "目标 Webhook ID")
    private Long webhookId;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "事件ID（与投递头 X-Finaudit-Delivery 一致，可用于与接收方对账）")
    private String eventId;

    @Schema(description = "状态: PENDING 待投递/待重试 / SUCCESS 成功 / DEAD 超次数放弃")
    private String status;

    @Schema(description = "已尝试次数")
    private Integer attemptCount;

    @Schema(description = "下次投递时间（PENDING 时有效）")
    private LocalDateTime nextRetryAt;

    @Schema(description = "最近一次 HTTP 状态码")
    private Integer lastHttpStatus;

    @Schema(description = "最近一次失败原因")
    private String lastError;

    @Schema(description = "投递成功时间")
    private LocalDateTime deliveredAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 实体 → VO。 */
    public static DeliveryVO from(NotifyDelivery entity) {
        DeliveryVO vo = new DeliveryVO();
        vo.setId(entity.getId());
        vo.setWebhookId(entity.getWebhookId());
        vo.setEventType(entity.getEventType());
        vo.setEventId(entity.getEventId());
        vo.setStatus(entity.getStatus());
        vo.setAttemptCount(entity.getAttemptCount());
        vo.setNextRetryAt(entity.getNextRetryAt());
        vo.setLastHttpStatus(entity.getLastHttpStatus());
        vo.setLastError(entity.getLastError());
        vo.setDeliveredAt(entity.getDeliveredAt());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
