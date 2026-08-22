package com.finaudit.starter.web.config;

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

/**
 * Web 通用能力自动装配：统一返回 / 全局异常 / 参数校验 / 租户上下文过滤 / 输出脱敏。
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
