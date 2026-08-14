package com.finaudit.starter.jwt;

import java.util.List;

/**
 * JWT 载荷解析结果：从 token 提取的登录主体信息。
 * <p>网关校验 token 后按此结构注入转发头；tenant-service 签发时组装主体信息，保证两端结构一致。</p>
 *
 * @param userId     用户 ID
 * @param tenantId   租户 ID
 * @param username   登录名
 * @param roles      角色编码列表（可为空）
 * @param jti        token 唯一标识（作废/登出黑名单用，签发时生成）
 * @param iatSeconds 签发时间（epoch 秒，用户级作废版本比较用）
 */
public record AuthClaims(Long userId, Long tenantId, String username, List<String> roles,
                         String jti, long iatSeconds) {
}
