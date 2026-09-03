package com.finaudit.agentcore.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * agent-core 应用级配置属性注册（Nacos 配置操作 + 管理员白名单 + 任务执行加固）。
 * <p>配置类统一 {@code @Getter @Setter}（默认值字段禁用 {@code @Data}，避免 Lombok 构造器丢失初始化器，
 * 见 CLAUDE.md §5.7）。</p>
 */
@Configuration
@EnableConfigurationProperties({NacosConfigProperties.class, AdminProperties.class,
        AgentExecutionProperties.class})
public class AgentCorePropertiesAutoConfiguration {
}
