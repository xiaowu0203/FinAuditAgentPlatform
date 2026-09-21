package com.finaudit.agentcore.pojo.entity;

import com.finaudit.agentcore.enums.WebhookDeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 投递台账状态机单测（P3.8 R8-2）。
 *
 * <p>投递是"会失败、要重试、最后要放弃"的链路，状态迁移算错的表现是**静默**的
 * （要么永远重试把台账刷爆，要么一次失败就再也不发）。故把三态迁移逐条钉住。</p>
 */
class NotifyDeliveryTest {

    private static NotifyDelivery pending() {
        return NotifyDelivery.pending(1L, 9L, "TICKET_APPROVED", "evt-1", Map.of("a", 1),
                LocalDateTime.now());
    }

    @Test
    void pendingDefaultsAreImmediatelyDeliverable() {
        NotifyDelivery d = pending();

        assertEquals(WebhookDeliveryStatus.PENDING.name(), d.getStatus());
        assertEquals(0, d.getAttemptCount());
        assertNotNull(d.getNextRetryAt(), "next_retry_at 是 NOT NULL 列，必须显式赋值");
        assertTrue(d.pending());
        assertFalse(d.dead());
    }

    /**
     * 到期时间必须截断到秒（P3.8 R8-2 运行时实测踩到）。
     *
     * <p>{@code next_retry_at} 是秒精度 DATETIME，而 MySQL 对超出精度的时间**四舍五入**——
     * 带 .8 秒的时间戳会被存成下一秒，于是"立刻到期"的行实际还差零点几秒；到期判定用的又是数据库
     * {@code NOW()}，结果是**手工催投当场漏掉刚登记的行**（实测该行随后因配置被清理而判 DEAD）。
     * 截断到秒后写入值 == 当前秒，与 {@code NOW()} 相等即到期。</p>
     */
    @Test
    void pendingTruncatesNextRetryToSecondPrecision() {
        LocalDateTime withNanos = LocalDateTime.now().withNano(987_654_321);

        NotifyDelivery d = NotifyDelivery.pending(1L, 9L, "TICKET_APPROVED", "evt-nano", Map.of(),
                withNanos);

        assertEquals(0, d.getNextRetryAt().getNano(),
                "必须截断到秒：否则超过 .5 秒会被 MySQL 四舍五入进下一秒，变成'还不该投'");
        assertEquals(withNanos.withNano(0), d.getNextRetryAt());
    }

    @Test
    void successRecordsStatusAndTime() {
        NotifyDelivery d = pending();
        d.applySuccess(200, LocalDateTime.now());

        assertEquals(WebhookDeliveryStatus.SUCCESS.name(), d.getStatus());
        assertEquals(200, d.getLastHttpStatus());
        assertNull(d.getLastError(), "成功后必须清掉上次的失败原因，否则台账自相矛盾");
        assertNotNull(d.getDeliveredAt());
        assertFalse(d.pending());
    }

    @Test
    void failureUnderLimitKeepsRetryingWithNextSchedule() {
        NotifyDelivery d = pending();
        LocalDateTime next = LocalDateTime.now().plusSeconds(10);

        d.setAttemptCount(1);
        boolean exhausted = d.applyFailure(500, "HTTP 500 boom", 3, next);

        assertFalse(exhausted, "1/3 次不应放弃");
        assertEquals(WebhookDeliveryStatus.PENDING.name(), d.getStatus());
        assertEquals(next, d.getNextRetryAt(), "必须排下次时间，否则这条记录再也不会被取到");
        assertEquals(500, d.getLastHttpStatus());
        assertNotNull(d.getLastError());
    }

    @Test
    void failureAtLimitBecomesDead() {
        NotifyDelivery d = pending();

        d.setAttemptCount(3);
        boolean exhausted = d.applyFailure(null, "connect timeout", 3, LocalDateTime.now());

        assertTrue(exhausted, "3/3 次应判死");
        assertEquals(WebhookDeliveryStatus.DEAD.name(), d.getStatus());
        assertTrue(d.dead(), "DEAD 是唯一需要告警的状态，前端/运维据此过滤");
    }

    @Test
    void deadReasonIsTruncatedToColumnWidth() {
        NotifyDelivery d = pending();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            sb.append('x');
        }
        d.applyFailure(null, sb.toString(), 1, LocalDateTime.now());

        assertEquals(NotifyDelivery.ERROR_MAX_LEN, d.getLastError().length(),
                "超长错误必须在实体边界截断：否则整行写入被 MySQL 拒绝，投递结果静默丢失（R9 error_msg 同款坑）");
    }

    @Test
    void applyDeadSkipsRetryEntirely() {
        NotifyDelivery d = pending();
        d.setAttemptCount(1);
        d.applyDead("地址不可用: 仅支持 http/https 协议");

        assertEquals(WebhookDeliveryStatus.DEAD.name(), d.getStatus());
        assertNotNull(d.getLastError());
        assertEquals(1, d.getAttemptCount(), "直接判死不消耗额度、不改次数");
    }

    @Test
    void resetForRetryMakesItDeliverableAgain() {
        NotifyDelivery d = pending();
        d.setAttemptCount(3);
        d.applyFailure(null, "boom", 3, LocalDateTime.now());
        assertTrue(d.dead());

        d.resetForRetry();

        assertEquals(WebhookDeliveryStatus.PENDING.name(), d.getStatus());
        assertEquals(0, d.getAttemptCount());
        assertNull(d.getLastError());
        assertTrue(d.getNextRetryAt().isBefore(LocalDateTime.now().plusSeconds(1)),
                "人工重投应立刻到期，而不是等下一轮退避");
    }
}
