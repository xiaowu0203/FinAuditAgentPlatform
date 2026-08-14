package com.finaudit.tenant.service;

import com.finaudit.starter.jwt.AuthSessionConstants;
import com.finaudit.starter.jwt.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 会话服务：token 作废（登出 / 用户级踢下线）的 Redis 写入侧。
 * <p>key/value 一律走 {@link StringRedisTemplate}（纯字符串），与网关校验侧
 * {@code ReactiveStringRedisTemplate} 序列化一致。切勿改用 common-redis-starter 的
 * JSON 序列化 RedisTemplate——它会把 "1"/时间戳写成带引号的 JSON 字符串，网关
 * {@code Long.parseLong} 解析版本号会失败、黑名单判断随之失效。</p>
 */
@Service
public class AuthSessionService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    public AuthSessionService(StringRedisTemplate stringRedisTemplate, JwtProperties jwtProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtProperties = jwtProperties;
    }

    /**
     * 登出：作废单个 token（按 jti）。
     * <p>TTL=token 完整生命周期（expireHours*3600），作为安全上界覆盖剩余有效期，到期自动清理。</p>
     */
    public void revoke(String jti) {
        long ttlSeconds = jwtProperties.getExpireHours() * 3600L;
        stringRedisTemplate.opsForValue()
                .set(AuthSessionConstants.BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 用户级作废：升级版本号为当前时间戳，使该用户所有已签发 token（iat ≤ 版本号）立即失效。
     * <p>用于「禁用用户 / 删除用户」等需踢下全部会话的场景。key 不设 TTL：数据极轻（每用户一条），
     * 且之后新签发 token 的 iat 必然晚于版本号，不受影响。</p>
     */
    public void revokeAll(Long userId) {
        stringRedisTemplate.opsForValue()
                .set(AuthSessionConstants.BLACKVER_PREFIX + userId, String.valueOf(Instant.now().getEpochSecond()));
    }
}
