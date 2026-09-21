package com.finaudit.agentcore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 主动通知配置（前缀 {@code finaudit.notify}，P3.8 R8-2）。
 *
 * <p>⚠️ 本类用 {@code @Getter @Setter} 而不是 {@code @Data}：Lombok 生成的构造器**不复制字段初始化器**，
 * 带默认值的配置类用 {@code @Data} 会让缺省值静默丢失（AGENTS.md §5.7）。</p>
 */
@ConfigurationProperties(prefix = "finaudit.notify")
@Getter
@Setter
public class NotifyProperties {

    /**
     * 是否允许 Webhook 投递到内网/环回地址。
     * <p><b>默认 false</b>：地址由租户填写、请求由服务端发起，放开等于给出一个 SSRF 跳板。
     * 仅本地联调/自测时开启（启动日志会打 WARN 提醒）。</p>
     */
    private boolean allowPrivateAddress = false;

    /** 定时投递开关（自测时可关掉，改用手工触发）。 */
    private boolean deliveryEnabled = true;

    /** 定时投递间隔（毫秒）。 */
    private long deliveryIntervalMs = 15_000L;

    /** 单轮最多取多少条待投递记录（避免一次拉太多把线程占满）。 */
    private int deliveryBatchSize = 50;

    /**
     * 失败重试退避（秒），按尝试次数取用，最后一项用尽后重复使用。
     * <p>缺省 1s → 10s → 60s：首次失败多半是瞬时抖动，快速重试一次；
     * 之后的失败更可能是对端故障，拉长间隔避免把对端打垮（也避免刷爆自己的台账）。</p>
     */
    private List<Integer> retryBackoffSeconds = List.of(1, 10, 60);

    /** 单次投递的建连超时（毫秒）。 */
    private int connectTimeoutMs = 3_000;

    /** 台账查询默认页大小。 */
    private int defaultPageSize = 20;

    /** 索引为 attempt 的退避秒数（attempt 从 1 开始：第一次失败后等 retryBackoffSeconds[0]）。 */
    public int backoffSecondsFor(int attemptCount) {
        if (retryBackoffSeconds == null || retryBackoffSeconds.isEmpty()) {
            return 10;
        }
        int idx = Math.max(0, Math.min(attemptCount - 1, retryBackoffSeconds.size() - 1));
        Integer seconds = retryBackoffSeconds.get(idx);
        return seconds == null || seconds < 1 ? 1 : seconds;
    }
}
