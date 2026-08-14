package com.finaudit.starter.feign;

import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Feign 统一自动配置：注册请求头/token 透传拦截器。
 * <p>各服务经 {@code @EnableFeignClients} 开启 Feign 扫描（业务 Feign 接口在各服务包内），
 * 本 starter 负责全局一致的鉴权/身份头透传，服务间调用无需各自实现。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class FeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RequestInterceptor feignHeaderPropagator() {
        return new FeignHeaderPropagator();
    }
}
