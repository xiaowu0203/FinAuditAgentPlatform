package com.finaudit.starter.web.config;

import com.finaudit.starter.web.exception.GlobalExceptionHandler;
import com.finaudit.starter.web.tenant.TenantIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Web 通用能力自动装配：统一返回 / 全局异常 / 参数校验 / 租户上下文过滤。
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
}
