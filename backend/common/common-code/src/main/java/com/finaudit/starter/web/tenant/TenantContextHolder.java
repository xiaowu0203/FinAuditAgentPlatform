package com.finaudit.starter.web.tenant;

/**
 * 当前线程租户上下文持有者。
 * <p>两条写入链：HTTP 链路由 {@link TenantIdFilter} 从 {@code X-Tenant-Id} 头写入；
 * MQ 消费链路由消费者在入口处用消息体 {@code tenantId} 写入（见 {@link #runWith}）。
 * 多租户拦截器 {@code TenantLineInnerInterceptor} 据此自动过滤；缺失时回退默认租户。</p>
 */
public final class TenantContextHolder {

    /** 租户 ID 请求头 */
    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    /** 缺省租户（P1 单租户环境默认租户） */
    public static final Long DEFAULT_TENANT_ID = 1L;

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static Long getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }

    /**
     * 在指定租户上下文下执行动作，执行结束（含异常）自动清理。
     * <p>供 MQ 消费者入口使用，防止 ThreadLocal 在线程池复用线程上泄漏。</p>
     *
     * @param tenantId 租户 ID
     * @param action   待执行动作
     */
    public static void runWith(Long tenantId, Runnable action) {
        setTenantId(tenantId);
        try {
            action.run();
        } finally {
            clear();
        }
    }

    /**
     * 在指定租户上下文下执行动作并返回结果，执行结束（含异常）自动清理。
     * <p>供登录等需先解析租户再查询的场景使用。</p>
     *
     * @param tenantId 租户 ID
     * @param action   待执行动作
     * @param <T>      返回类型
     * @return 动作执行结果
     */
    public static <T> T runWithResult(Long tenantId, java.util.function.Supplier<T> action) {
        setTenantId(tenantId);
        try {
            return action.get();
        } finally {
            clear();
        }
    }

    /**
     * 取当前租户 ID；上下文缺失时回退默认租户（与多租户拦截器一致）。
     */
    public static Long getTenantIdOrDefault() {
        Long tenantId = TENANT_ID.get();
        return tenantId != null ? tenantId : DEFAULT_TENANT_ID;
    }
}
