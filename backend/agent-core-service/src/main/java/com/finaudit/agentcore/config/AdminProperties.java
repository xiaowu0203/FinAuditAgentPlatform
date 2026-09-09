package com.finaudit.agentcore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 管理员配置（前缀 {@code finaudit.admin}）。
 * <p>P3.5 起规则配置管理主防线为类级 {@code @RequirePerm("rule:manage")} 权限码，
 * 本白名单保留为内部直连场景的第二道防线。网关经 JWT 注入 {@code X-User-Id}，下游据此校验。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "finaudit.admin")
public class AdminProperties {

    /** 规则配置管理员用户 ID 白名单（如 [1]），空列表则所有用户均非管理员 */
    private List<Long> userIds = List.of();
}
