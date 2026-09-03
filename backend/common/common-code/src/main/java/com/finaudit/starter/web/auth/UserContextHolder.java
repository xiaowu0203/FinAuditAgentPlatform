package com.finaudit.starter.web.auth;

/**
 * 当前线程登录用户上下文持有者。
 * <p>写入链：HTTP 链路由 {@link UserContextFilter} 从网关注入的
 * X-User-Id / X-Tenant-Id / X-Username / X-User-Roles / X-User-Perms / X-Dept-Id 头解析写入；
 * 非 HTTP 线程（MQ 消费）无上下文，业务需显式传参，与 TenantContextHolder 约定一致。</p>
 * <p>头值只信任网关注入（网关已剥离客户端伪造的身份头）；直连服务端口的调用视为开发场景。</p>
 */
public final class UserContextHolder {

    /** 用户 ID 请求头（网关注入） */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** 用户名请求头（网关注入） */
    public static final String USERNAME_HEADER = "X-Username";

    /** 角色编码请求头（网关注入，逗号分隔；权威为 Redis 快照，降级 JWT） */
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    /** 权限标识符请求头（网关注入，逗号分隔；权威为 Redis 快照） */
    public static final String USER_PERMS_HEADER = "X-User-Perms";

    /** 部门 ID 请求头（网关注入；P3.5b 部门实体） */
    public static final String DEPT_ID_HEADER = "X-Dept-Id";

    private static final ThreadLocal<UserContext> CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext context) {
        CONTEXT.set(context);
    }

    /** 当前用户上下文；未登录/非 HTTP 线程返回 null。 */
    public static UserContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 快捷判定当前用户是否拥有权限标识符；无上下文视为无权限。
     */
    public static boolean hasPerm(String code) {
        UserContext context = CONTEXT.get();
        return context != null && context.hasPerm(code);
    }
}
