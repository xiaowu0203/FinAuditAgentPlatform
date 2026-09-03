package com.finaudit.starter.web.auth;

import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 当前请求登录用户上下文（P3.5 权限标识符体系）。
 * <p>由 {@link UserContextFilter} 从网关注入的请求头解析写入
 * {@link UserContextHolder}；网关侧权威来源为 Redis 用户快照（角色/权限变更实时生效），
 * 快照缺失时降级用 JWT 内角色。业务代码禁止再手写解析 X-User-* 请求头。</p>
 *
 * <p>权限判定语义：{@code @RequirePerm} 任一命中即通过（any-of）；
 * 上下文缺失（内部调用/未登录）视为无任何权限，由拦截器 fail-closed 拒绝。</p>
 */
@Data
public class UserContext {

    /** 用户 ID（X-User-Id） */
    private Long userId;

    /** 租户 ID（X-Tenant-Id，与 TenantContextHolder 同源） */
    private Long tenantId;

    /** 登录名（X-Username） */
    private String username;

    /** 角色编码列表（X-User-Roles 逗号分隔，快照/JWT 降级） */
    private List<String> roles;

    /** 权限标识符集合（X-User-Perms 逗号分隔；系统管理操作级 + 业务资源级） */
    private Set<String> perms;

    /** 部门 ID（X-Dept-Id；P3.5b 部门实体落地前可为空） */
    private Long deptId;

    /** 是否拥有指定权限标识符。 */
    public boolean hasPerm(String code) {
        return perms != null && perms.contains(code);
    }

    /** 任一权限命中即返回 true（@RequirePerm 的 any-of 语义）。 */
    public boolean hasAnyPerm(String... codes) {
        if (perms == null || codes == null) {
            return false;
        }
        for (String code : codes) {
            if (perms.contains(code)) {
                return true;
            }
        }
        return false;
    }
}
