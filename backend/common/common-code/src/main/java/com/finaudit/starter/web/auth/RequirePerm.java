package com.finaudit.starter.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限校验注解
 * <p>支持标注在Controller类、Controller方法上，由 {@link PermissionInterceptor} 拦截器完成权限校验</p>
 * <p>生效规则：
 * <ul>
 *     <li>标注在方法：仅对当前接口方法生效</li>
 *     <li>标注在Controller类：对该控制器下全部接口生效；方法注解优先级高于类注解</li>
 *     <li>opt‑in模式：未添加该注解的接口不做权限校验，直接放行</li>
 *     <li>权限逻辑：传入多个权限标识，<b>拥有任意一个权限即可通过校验</b></li>
 * </ul>
 * </p>
 * <p>校验失败返回HTTP 403，输出统一JSON错误响应</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /**
     * 权限标识符数组，多个权限为「或关系」，任一命中即权限校验通过
     * <p>示例：@RequirePerm({"system:user:add","system:user:edit"})</p>
     * @return 权限标识集合
     */
    String[] value();
}
