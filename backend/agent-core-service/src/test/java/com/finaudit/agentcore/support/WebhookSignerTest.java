package com.finaudit.agentcore.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 签名单测（P3.8 R8-2）。
 *
 * <p>签名是"接收方凭什么相信这条请求来自本平台"的唯一依据，故三个易错点都钉住：
 * ① 基串必须含时间戳（否则可重放）；② 篡改 body/时间戳必须验签失败；
 * ③ 兼容带不带 {@code sha256=} 前缀两种写法（接收方实现各异，别让格式差异变成"验签不过"）。</p>
 */
class WebhookSignerTest {

    private static final String SECRET = "s3cr3t-key";
    private static final String BODY = "{\"eventType\":\"TICKET_APPROVED\",\"bizId\":7}";

    @Test
    void signAndVerifyRoundTrip() {
        long ts = 1767225600L;
        String header = WebhookSigner.headerValue(SECRET, ts, BODY);

        assertTrue(header.startsWith("sha256="), "头值应带算法前缀，实际=" + header);
        assertTrue(WebhookSigner.verify(SECRET, ts, BODY, header), "同密钥/同时间戳/同 body 必须验签通过");
    }

    @Test
    void verifyAcceptsBareHexWithoutPrefix() {
        long ts = 1767225600L;
        String hex = WebhookSigner.sign(SECRET, ts, BODY);

        assertTrue(WebhookSigner.verify(SECRET, ts, BODY, hex), "不带前缀的裸十六进制也应接受");
        assertTrue(WebhookSigner.verify(SECRET, ts, BODY, "sha256=" + hex));
    }

    @Test
    void tamperedBodyOrTimestampFailsVerification() {
        long ts = 1767225600L;
        String header = WebhookSigner.headerValue(SECRET, ts, BODY);

        assertFalse(WebhookSigner.verify(SECRET, ts, BODY + " ", header), "body 被改一个字符就必须失败");
        assertFalse(WebhookSigner.verify(SECRET, ts + 1, BODY, header),
                "时间戳被改就必须失败（时间戳参与签名 → 抓到旧请求改时间戳无法重放）");
        assertFalse(WebhookSigner.verify("another-secret", ts, BODY, header), "换密钥必须失败");
        assertFalse(WebhookSigner.verify(SECRET, ts, BODY, null), "缺签名头必须失败");
    }

    @Test
    void signatureBaseContainsTimestamp() {
        assertEquals("1767225600." + BODY, WebhookSigner.signatureBase(1767225600L, BODY),
                "签名基串必须是 timestamp.body —— 重放防护的基础");
    }

    @Test
    void signatureIsDeterministicAndSecretDependent() {
        long ts = 1000L;
        String a = WebhookSigner.sign(SECRET, ts, BODY);
        String b = WebhookSigner.sign(SECRET, ts, BODY);
        String c = WebhookSigner.sign("other", ts, BODY);

        assertEquals(a, b, "同输入必须同签名（接收方可独立复算）");
        assertNotEquals(a, c, "不同密钥必须不同签名");
        assertEquals(64, a.length(), "HMAC-SHA256 十六进制长度为 64");
    }
}
