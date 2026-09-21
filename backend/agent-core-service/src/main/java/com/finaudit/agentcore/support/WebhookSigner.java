package com.finaudit.agentcore.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Webhook 请求签名（P3.8 R8-2）。
 *
 * <p><b>签名方案</b>（接收方按此校验，文档见 {@code docs/api/notify.md}）：</p>
 * <pre>
 *   signature = HMAC-SHA256(secret, timestamp + "." + rawBody)
 *   X-Finaudit-Signature: sha256=&lt;hex&gt;
 *   X-Finaudit-Timestamp: &lt;epoch 秒&gt;
 * </pre>
 *
 * <p><b>为什么把 timestamp 一起签进去</b>：只签 body 的话，攻击者抓到一次合法请求就能无限重放
 * （body 不变、签名不变）。把时间戳纳入签名基串，接收方即可"校验签名 + 检查时间窗"两步挡住重放；
 * 时间戳本身被签名保护，改时间戳就会导致签名不匹配。</p>
 *
 * <p><b>为什么用 {@link MessageDigest#isEqual}</b>：字符串 {@code equals} 会在第一个不同字节处提前返回，
 * 理论上可被计时攻击逐字节猜出签名。签名校验必须走常量时间比较。</p>
 */
public final class WebhookSigner {

    /** 签名头名（与文档、验证脚本保持一致） */
    public static final String HEADER_SIGNATURE = "X-Finaudit-Signature";
    /** 时间戳头名 */
    public static final String HEADER_TIMESTAMP = "X-Finaudit-Timestamp";
    /** 事件类型头名 */
    public static final String HEADER_EVENT = "X-Finaudit-Event";
    /** 事件ID头名（接收方据此幂等去重） */
    public static final String HEADER_DELIVERY = "X-Finaudit-Delivery";

    /** 算法前缀（接收方可据此换代而不破坏兼容） */
    public static final String PREFIX = "sha256=";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private WebhookSigner() {
    }

    /** 签名基串：{@code timestamp + "." + body}。 */
    public static String signatureBase(long timestampSeconds, String body) {
        return timestampSeconds + "." + (body == null ? "" : body);
    }

    /** 计算签名（十六进制小写）。 */
    public static String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(signatureBase(timestampSeconds, body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // 算法/JDK 缺 HmacSHA256 属环境级故障（JDK 必然提供），此处抛出以免静默发出无签名请求
            throw new IllegalStateException("计算 Webhook 签名失败: " + e.getMessage(), e);
        }
    }

    /** 完整的签名头值：{@code sha256=<hex>}。 */
    public static String headerValue(String secret, long timestampSeconds, String body) {
        return PREFIX + sign(secret, timestampSeconds, body);
    }

    /**
     * 校验签名（常量时间比较）。接收方与验证脚本共用同一实现口径。
     *
     * @param secret       密钥
     * @param timestamp    请求头里的时间戳
     * @param body         原始 body（**必须是未改动的原文**）
     * @param headerValue  请求头 {@code X-Finaudit-Signature} 的值
     * @return true=签名有效
     */
    public static boolean verify(String secret, long timestamp, String body, String headerValue) {
        if (headerValue == null) {
            return false;
        }
        String provided = headerValue.startsWith(PREFIX) ? headerValue.substring(PREFIX.length()) : headerValue;
        String expected = sign(secret, timestamp, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
