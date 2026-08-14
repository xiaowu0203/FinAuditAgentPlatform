package com.finaudit.starter.swagger.config;

import com.finaudit.starter.swagger.properties.SwaggerProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Swagger/OpenAPI 通用自动装配：springdoc 开箱即用（/swagger-ui.html、/v3/api-docs），
 * 本类只补充统一的 {@link OpenAPI} 元信息 Bean。
 * <p>通过 {@code finaudit.swagger.enabled=false} 可关闭；生产环境建议同时配置
 * {@code springdoc.api-docs.enabled=false} 与 {@code springdoc.swagger-ui.enabled=false}。</p>
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@ConditionalOnProperty(prefix = "finaudit.swagger", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SwaggerProperties.class)
public class CommonSwaggerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI finauditOpenAPI(SwaggerProperties properties) {
        return new OpenAPI().info(new Info()
                .title(properties.getTitle())
                .description(properties.getDescription())
                .version(properties.getVersion()));
    }
}
