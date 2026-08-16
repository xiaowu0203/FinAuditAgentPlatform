package com.finaudit.starter.jwt.config;

import com.finaudit.starter.jwt.JwtProperties;
import com.finaudit.starter.jwt.JwtTokenProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;

/**
 * JWT 通用自动装配：绑定配置 + 提供签发/解析器。
 * <p>纯 Java 无 web 依赖，WebFlux 网关（agent-gateway）与 Servlet 业务服务（tenant-service 等）均可引入。</p>
 * <p>启动自检签名密钥：为空或不足 32 字节（HS256 下限）直接失败并给出明确提示，
 * 替代 JJWT {@code Keys.hmacShaKeyFor} 构造期抛出的晦涩 WeakKeyException。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class CommonJwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT 签名密钥未配置或过短：请设置 finaudit.jwt.secret（HS256 需 ≥32 字节），"
                            + "或经环境变量 FINAUDIT_JWT_SECRET 注入");
        }
        return new JwtTokenProvider(properties);
    }
}
