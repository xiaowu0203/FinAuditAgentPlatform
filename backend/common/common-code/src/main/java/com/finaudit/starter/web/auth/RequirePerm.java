package com.finaudit.starter.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限标识符声明式校验注解（P3.5 权限标识符体系）。
 * <p>由 {@link PermissionInterceptor} 执行：所列权限码<b>任一命中</b>（any-of）即放行；
 * 无上下文（未登录/内部直连）或全部未命中返回 HTTP 403 + R JSON，fail-closed。</p>
 * <p>可标注于方法或类：方法级存在时优先生效（覆盖类级），类级用于整组端点统一收口。</p>
 * <p>opt-in 语义：未标注的端点不做权限校验（登录即可），内部 Feign 复用端点不受影响。</p>
 *
 * <p>权限码示例：系统管理操作级 {@code user:create} / {@code role:assign-perm}；
 * 业务资源级 {@code reimb:viewAll} / {@code audit:approve}。目录见 migration-P3.5a.sql。</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /** 权限标识符列表，任一命中即通过 */
    String[] value();
}
