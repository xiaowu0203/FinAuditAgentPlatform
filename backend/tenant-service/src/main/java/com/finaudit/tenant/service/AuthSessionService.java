package com.finaudit.tenant.service;

import com.finaudit.starter.jwt.AuthSessionConstants;
import com.finaudit.starter.jwt.AuthSnapshot;
import com.finaudit.starter.jwt.JwtProperties;
import com.finaudit.starter.web.exception.BizException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 会话服务：token 作废（登出 / 用户级踢下线）、用户权限快照的 Redis 写入侧、
 * 登录失败锁定（P3.5d 防爆破）。
 * <p>key/value 一律走 {@link StringRedisTemplate}（纯字符串），与网关校验侧
 * {@code ReactiveStringRedisTemplate} 序列化一致。切勿改用 common-redis-starter 的
 * JSON 序列化 RedisTemplate——它会把 "1"/时间戳写成带引号的 JSON 字符串，网关
 * {@code Long.parseLong} 解析版本号会失败、黑名单判断随之失效。
 * 快照 value 为 {@link AuthSnapshot#toJson()} JSON 字符串（网关解析侧容错，损坏走降级）。</p>
 */
@Service
public class AuthSessionService {

    /** 登录锁定阈值：窗口期内连续失败达到该次数则临时锁定 */
    private static final int LOGIN_LOCK_MAX_FAILURES = 5;
    /** 登录失败计数窗口（同时也是锁定时长） */
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(15);
    /** 登录失败计数 key 前缀：{prefix}login:fail:{tenantId}:{username}，按租户+用户名维度隔离 */
    private static final String LOGIN_FAIL_PREFIX = AuthSessionConstants.AUTH_PREFIX + "login:fail:";

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

    /**
     * 写入用户权限快照（P3.5 实时生效）：登录与角色/权限/部门变更后调用，
     * 网关每请求读取并以快照为权威注入角色/权限/部门头。
     * <p>TTL=token 生命周期上界：覆盖已签发 token 的剩余有效期；重新登录或任何变更会重写续期。
     * 快照过期而 JWT 未过期时网关降级 JWT 角色（权限置空），属可接受的安全侧兜底。</p>
     */
    public void writeSnapshot(Long userId, AuthSnapshot snapshot) {
        long ttlSeconds = jwtProperties.getExpireHours() * 3600L;
        stringRedisTemplate.opsForValue()
                .set(AuthSessionConstants.SNAPSHOT_PREFIX + userId, snapshot.toJson(),
                        Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 删除用户权限快照（删除用户后清理；禁用用户由 blackver 踢下线兜底）。
     */
    public void deleteSnapshot(Long userId) {
        stringRedisTemplate.delete(AuthSessionConstants.SNAPSHOT_PREFIX + userId);
    }

    // ---------- 登录防爆破（P3.5d） ----------

    /**
     * 登录前锁定检查：窗口期内连续失败达 {@link #LOGIN_LOCK_MAX_FAILURES} 次则拒绝登录。
     * <p>已知取舍：按 租户+用户名 计数，攻击者可恶意锁死他人账号（登录侧 DoS）——
     * 内部财务平台可接受，彻底方案是网关按 IP 限流（P4+）。</p>
     *
     * @throws BizException 账号锁定中
     */
    public void assertLoginAllowed(Long tenantId, String username) {
        String failures = stringRedisTemplate.opsForValue().get(loginFailKey(tenantId, username));
        if (failures != null && parseIntSafe(failures) >= LOGIN_LOCK_MAX_FAILURES) {
            throw new BizException("登录失败次数过多，账号已临时锁定，请约 "
                    + LOGIN_FAILURE_WINDOW.toMinutes() + " 分钟后重试");
        }
    }

    /**
     * 记录一次登录失败：计数自增并刷新窗口。
     * <p>每次失败都续期 TTL（滑动窗口语义）而非仅首次设置——自愈：即便 increment 与
     * expire 之间进程中断遗留无 TTL key，下一次失败也会补上，避免永久锁死。</p>
     */
    public void recordLoginFailure(Long tenantId, String username) {
        String key = loginFailKey(tenantId, username);
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, LOGIN_FAILURE_WINDOW);
    }

    /**
     * 登录成功：清空失败计数。
     */
    public void clearLoginFailures(Long tenantId, String username) {
        stringRedisTemplate.delete(loginFailKey(tenantId, username));
    }

    private static String loginFailKey(Long tenantId, String username) {
        return LOGIN_FAIL_PREFIX + tenantId + ":" + username;
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // value 只由本服务 increment 写入，非数字仅可能是脏数据：按已锁定处理（fail-closed）
            return LOGIN_LOCK_MAX_FAILURES;
        }
    }
}
