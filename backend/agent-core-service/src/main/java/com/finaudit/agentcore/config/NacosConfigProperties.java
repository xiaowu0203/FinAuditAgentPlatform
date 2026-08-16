package com.finaudit.agentcore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租户级 Nacos 配置操作参数（前缀 {@code finaudit.nacos-config}）。
 * <p>供 {@link com.finaudit.agentcore.support.TenantNacosConfigHelper} 使用：订阅/缓存走
 * {@code NacosConfigManager}（复用 spring.cloud.nacos.config 的 8848 + namespace），发布走 Nacos 3
 * 控制台 API（登录 8848 → 写 8080）。用户名/密码在 application.yml 以环境变量占位，禁止硬编码。</p>
 * <p>⚠️ {@code namespace} 须与 {@code spring.cloud.nacos.config.namespace} 一致（当前 dev），
 * 否则发布与订阅看不到同一份配置。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "finaudit.nacos-config")
public class NacosConfigProperties {

    /** Nacos 核心服务地址（登录接口用），默认本机 8848 */
    private String coreAddr = "http://127.0.0.1:8848";

    /** Nacos 3 控制台 API 地址（发布配置用，前后端分离部署），默认本机 8080 */
    private String consoleAddr = "http://127.0.0.1:8080";

    /** Nacos 登录用户名（application.yml 走环境变量 NACOS_USERNAME） */
    private String username = "nacos";

    /** Nacos 登录密码（application.yml 走环境变量 NACOS_PASSWORD） */
    private String password = "nacos";

    /** 配置组 */
    private String group = "DEFAULT_GROUP";

    /** 命名空间（须与 spring.cloud.nacos.config.namespace 一致） */
    private String namespace = "dev";

    /** 财务规则 data-id 模板，{tenantId} 由 helper 替换为真实租户 ID */
    private String ruleDataId = "finaudit-rules-{tenantId}";

    /** 本地缓存 TTL（秒），仅兜底监听漏帧；监听事件驱动即时刷新 */
    private long cacheTtlSeconds = 60;
}
