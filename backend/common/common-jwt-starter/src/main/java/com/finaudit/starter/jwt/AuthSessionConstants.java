package com.finaudit.starter.jwt;

/**
 * 会话/作废相关 Redis key 常量。
 * <p>网关（校验侧）与 tenant-service（写入侧）共用，避免两处硬编码 key 前缀漂移导致黑名单失效。</p>
 */
public final class AuthSessionConstants {

    private AuthSessionConstants() {
    }

    /** Redis key 统一前缀 */
    public static final String AUTH_PREFIX = "finaudit:auth:";

    /** 单 token 作废黑名单 key 前缀：{@code finaudit:auth:blacklist:{jti}}，value=1，TTL=token 生命周期 */
    public static final String BLACKLIST_PREFIX = AUTH_PREFIX + "blacklist:";

    /** 用户级作废版本 key 前缀：{@code finaudit:auth:blackver:{userId}}，value=作废时间戳(epoch 秒) */
    public static final String BLACKVER_PREFIX = AUTH_PREFIX + "blackver:";

    /**
     * 用户权限快照 key 前缀（P3.5 实时生效）：{@code finaudit:auth:snapshot:{userId}}，
     * value={@link AuthSnapshot} JSON 字符串（StringRedisTemplate 纯字符串），
     * TTL=token 生命周期上界；角色/权限/部门变更时 tenant-service 重写。
     */
    public static final String SNAPSHOT_PREFIX = AUTH_PREFIX + "snapshot:";
}
