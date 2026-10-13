package com.finaudit.starter.feign;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feign 调用超时配置（P3.8 R7-3，前缀 {@code finaudit.feign}）。
 *
 * <p><b>为什么必须显式配置</b>：Feign 不设超时时用的是框架缺省值——读超时 <b>60 秒</b>。
 * 服务间调用一旦对端假死（线程池打满、GC 卡顿、网络黑洞），调用方线程会被占住整整一分钟；
 * 在 agent-core 这条链路上，一个卡住的 Feign 调用会连带把 MQ 消费线程和任务推进一起拖停，
 * 表现为「任务长期 RUNNING、无任何错误日志」——极难定位。</p>
 *
 * <p>缺省值取「内网调用」口径：建连 3 秒、读 10 秒。超过就快速失败，
 * 由业务层的重试/降级（如 tool-service 的缓存降级、agent-core 的任务级超时）接手。</p>
 *
 * <p>⚠️ 用 {@code @Getter @Setter} 而非 {@code @Data}：Lombok 生成的构造器不复制字段初始化器，
 * 带默认值的配置类用 {@code @Data} 会让默认值静默丢失（见 AGENTS.md §5.7）。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "finaudit.feign")
public class FeignTimeoutProperties {

    /** 建连超时（毫秒），默认 3000 */
    private int connectTimeoutMs = 3000;

    /** 读超时（毫秒），默认 10000 */
    private int readTimeoutMs = 10000;

    /** 是否跟随重定向（默认 true，与 Feign 缺省一致） */
    private boolean followRedirects = true;
}
