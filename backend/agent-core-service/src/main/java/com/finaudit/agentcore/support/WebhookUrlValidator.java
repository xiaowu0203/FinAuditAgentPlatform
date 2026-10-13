package com.finaudit.agentcore.support;

import com.finaudit.agentcore.config.NotifyProperties;
import com.finaudit.starter.web.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Webhook 地址安全校验（P3.8 R8-2）。
 *
 * <p><b>要防的是什么</b>：Webhook 地址是**租户填的**，而投递由服务端发起——这正是经典 SSRF
 * （服务端请求伪造）。不设防时，一个能配 Webhook 的账号可以：
 * ① 让服务端去请求内网地址（{@code http://127.0.0.1:8848/nacos}、{@code http://10.0.0.5:9201/internal/...}）
 * 把内部接口当跳板；② 打云厂商元数据地址 {@code 169.254.169.254} 窃取实例凭据。</p>
 *
 * <p><b>校验规则</b>：仅允许 {@code http}/{@code https}；主机必须能解析；解析出的**每个**地址都不得是
 * 环回/私网/链路本地/唯一本地(IPv6 fc00::/7)/组播/通配地址。确需联调内网地址时，通过
 * {@code finaudit.notify.allow-private-address=true} 显式放开（默认关闭，且会在启动日志里告警）。</p>
 *
 * <p><b>⚠️ 残余风险（诚实标注）</b>：本校验在"配置时"和"每次投递前"各做一次，
 * 但 DNS 解析与后续 HTTP 连接之间存在 TOCTOU 窗口，理论上仍可通过 DNS rebinding
 * （先解析到公网、连接时改指向内网）绕过。要彻底消除需把校验后的 IP 钉死用于连接
 * （自定义 {@code HttpClient} 的 DNS 解析），本阶段不做，已在 {@code docs/api/notify.md} 登记。</p>
 */
@Component
public class WebhookUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookUrlValidator.class);

    private final NotifyProperties properties;

    public WebhookUrlValidator(NotifyProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验地址可用性；不合法直接抛 {@link BizException}（配置场景：让用户看到可读原因）。
     *
     * @param url 待校验地址
     */
    public void validate(String url) {
        String reason = check(url);
        if (reason != null) {
            throw new BizException("Webhook 地址不可用: " + reason);
        }
    }

    /**
     * 校验并返回失败原因（投递场景：不想用异常控制流程，只需要"能不能发"）。
     *
     * @param url 待校验地址
     * @return null = 通过；非 null = 不可用原因
     */
    public String check(String url) {
        if (url == null || url.isBlank()) {
            return "地址为空";
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "地址格式非法（无法解析为 URI）";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "仅支持 http/https 协议（实际: " + scheme + "）";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "缺少主机名";
        }
        // URI.getHost() 对 IPv6 字面量会带方括号（http://[::1]:8080 → "[::1]"），
        // 直接交给 InetAddress 会解析失败并被报成"主机无法解析"，掩盖真正的拒绝原因
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (properties.isAllowPrivateAddress()) {
            // 放开时仍然要求可解析：地址不可解析的配置在投递时只会不断失败
            try {
                InetAddress.getAllByName(host);
                return null;
            } catch (UnknownHostException e) {
                return "主机无法解析: " + host;
            }
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "主机无法解析: " + host;
        }
        if (addresses.length == 0) {
            return "主机无法解析: " + host;
        }
        for (InetAddress address : addresses) {
            String block = privateAddressReason(address);
            if (block != null) {
                return "解析到内网/保留地址 " + address.getHostAddress() + "（" + block
                        + "）；如确需联调内网地址，请开启 finaudit.notify.allow-private-address";
            }
        }
        return null;
    }

    /**
     * 判断是否为内网/保留地址；返回原因（null = 公网可用地址）。
     *
     * <p>判定顺序刻意从具体到笼统：组播/通配/环回/链路本地都有 JDK 现成方法，
     * 而 IPv6 唯一本地地址（fc00::/7）JDK 的 {@code isSiteLocalAddress()} 并不覆盖
     * （它只认已废弃的 fec0::/10），必须自己按首字节判定；IPv4-mapped IPv6
     * （{@code ::ffff:10.0.0.1}）也要还原成 IPv4 再判，否则能绕过检查。</p>
     */
    private static String privateAddressReason(InetAddress address) {
        if (address.isMulticastAddress()) {
            return "组播地址";
        }
        if (address.isAnyLocalAddress()) {
            return "通配地址 0.0.0.0/::";
        }
        if (address.isLoopbackAddress()) {
            return "环回地址";
        }
        if (address.isLinkLocalAddress()) {
            return "链路本地地址（含云元数据 169.254.169.254）";
        }
        if (address.isSiteLocalAddress()) {
            return "私网地址（10/8、172.16/12、192.168/16）";
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            // IPv4-mapped：前 10 字节 0 + 0xff 0xff + IPv4 四字节
            if (isIpv4Mapped(bytes)) {
                try {
                    return privateAddressReason(InetAddress.getByAddress(
                            new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}));
                } catch (UnknownHostException e) {
                    return "非法的 IPv4-mapped 地址";
                }
            }
            // IPv6 唯一本地地址 fc00::/7
            if ((bytes[0] & 0xFE) == 0xFC) {
                return "IPv6 唯一本地地址（fc00::/7）";
            }
        }
        return null;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    /** 启动期提示：放开了内网地址时把风险说清楚（运维最可能在联调后忘了关）。 */
    public void logConfigurationOnStartup() {
        if (properties.isAllowPrivateAddress()) {
            log.warn("⚠️ finaudit.notify.allow-private-address=true：Webhook 允许投递到内网/环回地址，"
                    + "仅可用于本地联调，生产环境必须关闭（否则任意可配 Webhook 的账号可借服务端探测内网）");
        }
    }
}
