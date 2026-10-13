package com.finaudit.agentcore.support;

import com.finaudit.agentcore.config.NotifyProperties;
import com.finaudit.starter.web.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Webhook 地址安全校验单测（P3.8 R8-2，SSRF 防线）。
 *
 * <p>用例刻意只用 IP 字面量，不解析公网域名：单测不应依赖 DNS 与外网可达性，
 * 否则离线环境会变成"随机失败"（这正是本仓踩过的"前置条件不满足被当成缺陷"的同款陷阱）。</p>
 */
class WebhookUrlValidatorTest {

    private static WebhookUrlValidator validator(boolean allowPrivate) {
        NotifyProperties props = new NotifyProperties();
        props.setAllowPrivateAddress(allowPrivate);
        return new WebhookUrlValidator(props);
    }

    @Test
    void rejectsNonHttpSchemes() {
        WebhookUrlValidator v = validator(false);
        assertNotNull(v.check("ftp://127.0.0.1/x"), "ftp 必须拒绝");
        assertNotNull(v.check("file:///etc/passwd"), "file 必须拒绝");
        assertNotNull(v.check("javascript:alert(1)"), "非网络协议必须拒绝");
        assertNotNull(v.check(""), "空地址必须拒绝");
        assertNotNull(v.check(null), "null 必须拒绝");
    }

    @Test
    void rejectsPrivateAndReservedAddressesByDefault() {
        WebhookUrlValidator v = validator(false);

        assertNotNull(v.check("http://127.0.0.1:8080/hook"), "环回地址必须拒绝");
        assertNotNull(v.check("http://10.1.2.3/hook"), "私网 10/8 必须拒绝");
        assertNotNull(v.check("http://192.168.1.10/hook"), "私网 192.168/16 必须拒绝");
        assertNotNull(v.check("http://172.16.0.9/hook"), "私网 172.16/12 必须拒绝");
        assertNotNull(v.check("http://169.254.169.254/latest/meta-data/"),
                "云元数据地址（链路本地）必须拒绝——这是 SSRF 最典型的目标");
        assertNotNull(v.check("http://0.0.0.0/hook"), "通配地址必须拒绝");
        assertNotNull(v.check("http://[::1]:8080/hook"), "IPv6 环回必须拒绝（且不能误报成'无法解析'）");
    }

    @Test
    void allowPrivateFlagIsTheOnlyWayThroughForInternalAddresses() {
        // 默认拒绝 → 显式放开后才通过；两条断言必须成对，否则"放开开关失效"不会被发现
        assertNotNull(validator(false).check("http://127.0.0.1:18080/hook"));
        assertNull(validator(true).check("http://127.0.0.1:18080/hook"),
                "开启 allow-private-address 后本地联调地址应通过");
    }

    @Test
    void validateThrowsReadableBizExceptionForConfigPath() {
        WebhookUrlValidator v = validator(false);
        BizException e = assertThrows(BizException.class, () -> v.validate("http://127.0.0.1/hook"));
        // 配置场景需要人能看懂"为什么不行、怎么放开"
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("内网")
                        || e.getMessage().contains("环回"), "异常信息应说明原因，实际=" + e.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("allow-private-address"),
                "异常信息应给出放开方式，实际=" + e.getMessage());
    }

    @Test
    void publicAddressWithPortAndPathPasses() {
        // 8.8.8.8 是公网字面量，解析不需要 DNS，可离线执行
        assertDoesNotThrow(() -> validator(false).validate("https://8.8.8.8:8443/webhook?token=1"));
    }
}
