package com.finaudit.agentcore.pojo.entity;

import com.finaudit.agentcore.pojo.dto.WebhookCreateRequest;
import com.finaudit.agentcore.pojo.dto.WebhookUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 配置实体单测（P3.8 R8-2）。
 *
 * <p>三个易错点：① 空订阅数组 = 全部（新建时不该强迫管理员勾一遍）；
 * ② 密钥只回显掩码（明文回显等于送出"伪造平台签名"的能力）；
 * ③ 部分更新的"空值不动"语义（尤其 secret 留空不能把密钥清成空串，那会让签名全废）。</p>
 */
class NotifyWebhookTest {

    private static WebhookCreateRequest create(String url, String secret) {
        return new WebhookCreateRequest("OA 系统", url, secret, List.of(), 1, null, null);
    }

    @Test
    void emptyEventTypesMeansSubscribeAll() {
        NotifyWebhook w = NotifyWebhook.from(create("http://8.8.8.8/hook", "k"), 1L, 7L);

        assertTrue(w.subscribes("TICKET_APPROVED"), "空数组应视为订阅全部");
        assertTrue(w.subscribes("MQ_DLQ_ALERT"));
        assertFalse(w.subscribes(null), "null 事件码必须 false，不能 NPE");
    }

    @Test
    void explicitSubscriptionsFilterEvents() {
        NotifyWebhook w = NotifyWebhook.from(new WebhookCreateRequest("OA", "http://8.8.8.8/hook", "k",
                List.of("TICKET_APPROVED", "TICKET_REJECTED"), 1, null, null), 1L, 7L);

        assertTrue(w.subscribes("TICKET_APPROVED"));
        assertFalse(w.subscribes("TICKET_CREATED"), "未订阅的事件不得触发投递");
    }

    @Test
    void defaultsAndBoundsAreNormalized() {
        NotifyWebhook w = NotifyWebhook.from(create("http://8.8.8.8/hook", "k"), 1L, 7L);

        assertEquals(NotifyWebhook.DEFAULT_MAX_ATTEMPTS, w.getMaxAttempts());
        assertEquals(NotifyWebhook.DEFAULT_TIMEOUT_MS, w.getTimeoutMs());
        assertEquals(1, w.getEnabled());

        NotifyWebhook extreme = NotifyWebhook.from(new WebhookCreateRequest("x", "http://8.8.8.8/h", "k",
                null, 1, 999, 999_999), 1L, null);
        assertEquals(10, extreme.getMaxAttempts(), "次数上限 10：再多只是拖延承认失败");
        assertEquals(60_000, extreme.getTimeoutMs(), "超时上限 60s");
    }

    @Test
    void secretIsNeverReturnedInPlainText() {
        NotifyWebhook w = NotifyWebhook.from(create("http://8.8.8.8/hook", "abcdefgh"), 1L, 1L);

        assertEquals("ab****gh", w.maskedSecret());
        assertFalse(w.maskedSecret().contains("cdef"), "掩码不得泄露中间字符");

        NotifyWebhook shortSecret = NotifyWebhook.from(create("http://8.8.8.8/hook", "abc"), 1L, 1L);
        assertEquals("****", shortSecret.maskedSecret(), "过短密钥整体打码");
    }

    @Test
    void updateKeepsUnspecifiedFieldsIncludingSecret() {
        NotifyWebhook w = NotifyWebhook.from(create("http://8.8.8.8/old", "original-secret"), 1L, 1L);

        // 只改 URL（前端不回显密钥，故 secret 传空串）
        w.apply(new WebhookUpdateRequest(null, "http://8.8.8.8/new", "", null, null, null, null));

        assertEquals("http://8.8.8.8/new", w.getUrl());
        assertEquals("original-secret", w.getSecret(), "空 secret 必须保留原密钥，否则签名全部失效");
        assertEquals("OA 系统", w.getName(), "未传的字段不得被清空");
        assertEquals(1, w.getEnabled());
        assertTrue(w.subscribes("TASK_FAILED"), "未传 eventTypes 时订阅范围不变");
    }

    @Test
    void updateReplacesEventTypesWhenProvided() {
        NotifyWebhook w = NotifyWebhook.from(create("http://8.8.8.8/hook", "k"), 1L, 1L);

        w.apply(new WebhookUpdateRequest(null, null, null, List.of("TASK_FAILED"), 0, 5, 8000));

        assertTrue(w.subscribes("TASK_FAILED"));
        assertFalse(w.subscribes("TICKET_APPROVED"), "显式传了数组就要覆盖，而不是合并");
        assertEquals(0, w.getEnabled());
        assertFalse(w.enabled(), "停用后 enabled() 必须为 false（投递侧据此跳过）");
        assertEquals(5, w.getMaxAttempts());
        assertEquals(8000, w.getTimeoutMs());
    }

    @Test
    void oversizedFieldsAreTruncatedAtEntityBoundary() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('n');
        }
        NotifyWebhook w = NotifyWebhook.from(new WebhookCreateRequest(longName.toString(),
                "http://8.8.8.8/hook", "k", null, 1, 1, 1000), 1L, 1L);

        assertEquals(NotifyWebhook.NAME_MAX_LEN, w.getName().length(), "名称超长必须截断而不是让写入失败");
        assertEquals(1L, w.getCreatedBy(), "创建人透传（用于投递失败时告警到人）");
    }
}
