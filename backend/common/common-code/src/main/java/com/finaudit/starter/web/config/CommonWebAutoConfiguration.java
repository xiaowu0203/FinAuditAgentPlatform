package com.finaudit.starter.web.config;

import com.finaudit.starter.web.auth.PermissionInterceptor;
import com.finaudit.starter.web.auth.UserContextFilter;
import com.finaudit.starter.web.exception.GlobalExceptionHandler;
import com.finaudit.starter.web.mask.jackson.MaskIntrospector;
import com.finaudit.starter.web.tenant.TenantIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 通用能力自动装配：统一返回 / 全局异常 / 参数校验 / 租户上下文过滤 / 输出脱敏 / 权限标识符校验。
 */
@AutoConfiguration
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    /**
     * 租户上下文过滤器：从 X-Tenant-Id 头写入 {@code TenantContextHolder}。
     * 高优先级，先于业务拦截器执行；Servlet Web 环境生效（网关 WebFlux 不引入本 starter）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public TenantIdFilter tenantIdFilter() {
        return new TenantIdFilter();
    }

    /**
     * 用户上下文过滤器（P3.5）：从网关注入的 X-User-* 头解析 {@code UserContext}
     * 写入 {@code UserContextHolder}。置于租户过滤器之后、权限拦截器之前。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public UserContextFilter userContextFilter() {
        return new UserContextFilter();
    }

    /**
     * 权限校验拦截器（P3.5）：执行 {@code @RequirePerm} 声明式权限标识符校验。
     * <p>独立 bean + 按自身类型条件化：服务可自供 {@link PermissionInterceptor} 覆盖。
     * ⚠️ 注册器（{@link #permissionInterceptorRegistrar}）不得用
     * {@code @ConditionalOnMissingBean(WebMvcConfigurer.class)}——springdoc 等会注册
     * 自己的 WebMvcConfigurer（OpenApiWebMvcConfigurer），条件必假导致拦截器被静默跳过。</p>
     */
    @Bean
    @ConditionalOnMissingBean(PermissionInterceptor.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public PermissionInterceptor permissionInterceptor() {
        return new PermissionInterceptor();
    }

    /**
     * 权限拦截器注册（P3.5）：恒注册（opt-in——未标注 @RequirePerm 端点不受影响）。
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public WebMvcConfigurer permissionInterceptorRegistrar(PermissionInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/**");
            }
        };
    }

    /**
     * 输出脱敏：把 {@link MaskIntrospector} 挂到 Spring 管理的 ObjectMapper 上，
     * 使标注了 {@code @Mask} 的对外 VO 字段在序列化时脱敏（税号/手机号等；金额不脱敏）。
     * 对所有依赖 common-code 的 Web 服务生效；仅影响 write，不影响反序列化与内部 DTO。
     */
    @Bean
    @ConditionalOnMissingBean
    public Jackson2ObjectMapperBuilderCustomizer maskJacksonCustomizer() {
        return builder -> builder.annotationIntrospector(new MaskIntrospector());
    }
}
