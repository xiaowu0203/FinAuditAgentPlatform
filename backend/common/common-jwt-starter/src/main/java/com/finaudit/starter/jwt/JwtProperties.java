package com.finaudit.starter.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（前缀 {@code finaudit.jwt}）。
 * <p>secret 走环境变量 {@code FINAUDIT_JWT_SECRET}（HS256 需 ≥32 字节），禁止硬编码入库。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "finaudit.jwt")
public class JwtProperties {

    /** 签名密钥（HS256，≥32 字节） */
    private String secret = "";

    /** 令牌有效期（小时），默认 24 */
    private long expireHours = 24;

    /** 签发者 */
    private String issuer = "finaudit";
}
