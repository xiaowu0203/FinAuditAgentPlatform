package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.WebhookDeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Webhook 投递台账（notify_delivery，P3.8 R8-2，outbox 模式）。
 *
 * <p><b>为什么用 DB outbox 而不是直接发/丢 MQ</b>：投递行与业务数据在**同一事务**写入，
 * 于是"业务提交成功 ⇒ 投递行必在"，既不需要额外的 MQ 拓扑，也不怕进程重启丢消息；
 * 重试只是把行重新置为可投递状态，人工重投也只是改一行。代价是多一次表写入与一个定时任务。</p>
 *
 * <p><b>状态机</b>：{@code PENDING}（待投递/待重试）→ {@code SUCCESS} 或 {@code DEAD}（超次数放弃）。
 * 刻意没有 {@code FAILED} 中间态：一次失败只是"还没成功"，把 attempt_count 与 next_retry_at 记下来即可；
 * 多一个状态只会让"这条到底还会不会再发"变得需要推理。</p>
 */
@Getter
@Setter
@TableName(value = "notify_delivery", autoResultMap = true)
public class NotifyDelivery {

    /** last_error 上限（与 DDL 一致；实体边界截断，避免超长把整行写失败） */
    public static final int ERROR_MAX_LEN = 500;
    /** event_id 上限 */
    public static final int EVENT_ID_MAX_LEN = 64;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "目标 Webhook")
    private Long webhookId;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "事件ID（投递头 X-Finaudit-Delivery）")
    private String eventId;

    /** JSON 列：原始事件体，重试原样重发（签名基于同一 body） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "事件负载")
    private Map<String, Object> payload;

    @Schema(description = "状态: PENDING / SUCCESS / DEAD")
    private String status;

    @Schema(description = "已尝试次数")
    private Integer attemptCount;

    @Schema(description = "下次投递时间")
    private LocalDateTime nextRetryAt;

    @Schema(description = "最近一次 HTTP 状态码")
    private Integer lastHttpStatus;

    @Schema(description = "最近一次失败原因")
    private String lastError;

    @Schema(description = "投递成功时间")
    private LocalDateTime deliveredAt;

    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /** 新建一条待投递记录（静态工厂）。 */
    public static NotifyDelivery pending(Long tenantId, Long webhookId, String eventType, String eventId,
                                         Map<String, Object> payload, LocalDateTime nextRetryAt) {
        NotifyDelivery d = new NotifyDelivery();
        d.setTenantId(tenantId);
        d.setWebhookId(webhookId);
        d.setEventType(eventType);
        d.setEventId(truncate(eventId, EVENT_ID_MAX_LEN));
        d.setPayload(payload);
        d.setStatus(WebhookDeliveryStatus.PENDING.name());
        d.setAttemptCount(0);
        // NOT NULL 列必须显式赋值：首次投递即刻到期。
        // ⚠️ 必须**截断到秒**：本列是 DATETIME（0 位小数），而 MySQL 写库时对超出精度的时间是**四舍五入**——
        //    带 .8 秒的时间戳会变成下一秒，于是"立刻到期"的行实际还差零点几秒，
        //    而到期判定（selectPendingTenantIds 用 DB 的 NOW()）此时看不到它。
        //    实测现象：手工催投当场漏掉这条，紧接着脚本清理删掉配置，定时任务再取到时只能判 DEAD。
        //    截断到秒后，写入值 == 当前秒，与 NOW() 相等即到期，不再依赖亚秒时序。
        LocalDateTime due = (nextRetryAt == null ? LocalDateTime.now() : nextRetryAt).withNano(0);
        d.setNextRetryAt(due);
        return d;
    }

    public boolean pending() {
        return WebhookDeliveryStatus.PENDING.name().equals(status);
    }

    public boolean dead() {
        return WebhookDeliveryStatus.DEAD.name().equals(status);
    }

    /** 投递成功。 */
    public void applySuccess(int httpStatus, LocalDateTime at) {
        this.status = WebhookDeliveryStatus.SUCCESS.name();
        this.lastHttpStatus = httpStatus;
        this.lastError = null;
        this.deliveredAt = at == null ? LocalDateTime.now() : at;
    }

    /**
     * 投递失败。**由本方法决定"还会不会再试"**：还有额度则留 PENDING 并排下次时间，
     * 用尽则置 DEAD（DEAD 才是需要告警的状态）。
     *
     * @param httpStatus   HTTP 状态码（网络层失败传 null）
     * @param error        失败原因（按列宽截断）
     * @param maxAttempts  该 Webhook 配置的最大投递次数
     * @param nextRetryAt  下次投递时间（仅当还能重试时有意义）
     * @return true = 已耗尽次数进入 DEAD
     */
    public boolean applyFailure(Integer httpStatus, String error, int maxAttempts, LocalDateTime nextRetryAt) {
        this.lastHttpStatus = httpStatus;
        this.lastError = truncate(error, ERROR_MAX_LEN);
        boolean exhausted = this.attemptCount != null && this.attemptCount >= maxAttempts;
        if (exhausted) {
            this.status = WebhookDeliveryStatus.DEAD.name();
        } else {
            this.status = WebhookDeliveryStatus.PENDING.name();
            this.nextRetryAt = nextRetryAt == null ? LocalDateTime.now() : nextRetryAt;
        }
        return exhausted;
    }

    /** 人工重投：清零次数并立刻到期（仅对 DEAD/长期失败的记录有意义）。 */
    public void resetForRetry() {
        this.status = WebhookDeliveryStatus.PENDING.name();
        this.attemptCount = 0;
        this.nextRetryAt = LocalDateTime.now();
        this.lastError = null;
    }

    /**
     * 直接判死（不再重试）。
     *
     * <p>用于"重试也没有意义"的失败：配置已删除/停用、地址不合法（协议或内网地址）。
     * 与 {@link #applyFailure} 的区别是不看次数——这类原因重试一万次也是同样结果，
     * 留着 PENDING 只会让台账里堆满永不成功的待办。</p>
     */
    public void applyDead(String reason) {
        this.status = WebhookDeliveryStatus.DEAD.name();
        this.lastError = truncate(reason, ERROR_MAX_LEN);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
