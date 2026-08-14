package com.finaudit.starter.jwt.config;

import com.finaudit.starter.jwt.JwtProperties;
import com.finaudit.starter.jwt.JwtTokenProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * JWT 通用自动装配：绑定配置 + 提供签发/解析器。
 * <p>纯 Java 无 web 依赖，WebFlux 网关（agent-gateway）与 Servlet 业务服务（tenant-service 等）均可引入。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class CommonJwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        return new JwtTokenProvider(properties);
    }
}
