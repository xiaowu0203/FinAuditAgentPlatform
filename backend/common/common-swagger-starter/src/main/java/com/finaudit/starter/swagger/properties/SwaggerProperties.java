package com.finaudit.starter.swagger.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Swagger 元信息配置：{@code finaudit.swagger.*}。
 * <p>各服务在 yml 中覆盖即可，如：</p>
 * <pre>
 * finaudit:
 *   swagger:
 *     enabled: true
 *     title: agent-core-service API
 *     description: 任务调度与多智能体编排接口
 *     version: 0.1.0
 * </pre>
 */
@ConfigurationProperties(prefix = "finaudit.swagger")
public class SwaggerProperties {

    /** 是否装配 OpenAPI（生产环境关闭时请同时关掉 springdoc.api-docs/swagger-ui） */
    private boolean enabled = true;

    /** 文档标题 */
    private String title = "FinAuditAgentPlatform";

    /** 文档描述 */
    private String description = "财务费用智能审核 Agent 平台 API";

    /** 文档版本 */
    private String version = "0.1.0";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
