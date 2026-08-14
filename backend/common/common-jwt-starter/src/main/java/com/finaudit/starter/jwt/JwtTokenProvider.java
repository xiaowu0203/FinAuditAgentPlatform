package com.finaudit.starter.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT令牌生成与解析工具类
 * 基于JJWT实现HS256对称签名；
 * 支持生成携带jti（令牌唯一编号）、iat签发时间，用于配合Redis实现令牌吊销：
 * 1. jti：单设备登出黑名单；
 * 2. iat：签发时间戳，用于用户全局会话版本校验，实现一键踢全部终端。
 */
public class JwtTokenProvider {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成JWT AccessToken
     * @param userId 用户ID
     * @param tenantId 租户ID（多租户核心字段）
     * @param username 用户名，作为jwt subject
     * @param roles 用户角色列表
     * @return 签名完成的JWT字符串
     */
    public String createToken(Long userId, Long tenantId, String username, List<String> roles) {
        Instant now = Instant.now();
        List<String> roleList = roles == null ? List.of() : roles;
        return Jwts.builder()
                // iss 签发者标识
                .issuer(properties.getIssuer())
                // sub 用户名
                .subject(username)
                // iat 令牌签发时间
                .issuedAt(Date.from(now))
                // exp 令牌过期时间 = 当前时间 + 配置的小时数
                .expiration(Date.from(now.plusSeconds(properties.getExpireHours() * 3600)))
                // jti JWT唯一编号，用于单token黑名单吊销
                .id(UUID.randomUUID().toString())
                // 自定义载荷：用户ID
                .claim("userId", userId)
                // 自定义载荷：租户ID
                .claim("tenantId", tenantId)
                // 自定义载荷：角色集合
                .claim("roles", roleList)
                // HS256对称密钥签名
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验JWT令牌
     * 自动校验：签名合法性、令牌是否过期、格式合法性；校验失败抛出JwtException
     * @param token JWT字符串
     * @return 封装身份实体AuthClaims，包含userId、tenantId、username、roles、jti、iat签发时间戳(秒)
     * @throws JwtException token为空、签名篡改、令牌过期、格式异常时抛出
     */
    public AuthClaims parseToken(String token) {
        // 判空
        if (!StringUtils.hasText(token)) {
            throw new JwtException("token 为空");
        }

        // verifyWith 校验签名；过期会自动抛出 ExpiredJwtException
        Claims body = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // 获取用户的角色列表
        List<String> roles = body.get("roles", List.class);

        // 获取签发时间Date对象，用于转成秒级时间戳
        Date iat = body.getIssuedAt();
        return new AuthClaims(
                body.get("userId", Long.class),
                body.get("tenantId", Long.class),
                body.getSubject(),
                roles == null ? List.of() : roles,
                // jti 令牌唯一ID
                body.getId(),
                // iat 签发时间戳，单位秒，用于全局会话版本校验
                iat == null ? 0L : iat.toInstant().getEpochSecond());
    }
}
