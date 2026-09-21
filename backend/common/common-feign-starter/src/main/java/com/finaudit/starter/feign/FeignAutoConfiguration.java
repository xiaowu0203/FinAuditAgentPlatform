package com.finaudit.starter.feign;

import feign.Request;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * Feign 统一自动配置：注册请求头/token 透传拦截器 + 全局超时。
 * <p>各服务经 {@code @EnableFeignClients} 开启 Feign 扫描（业务 Feign 接口在各服务包内），
 * 本 starter 负责全局一致的鉴权/身份头透传与调用超时，服务间调用无需各自实现。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
@EnableConfigurationProperties(FeignTimeoutProperties.class)
public class FeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RequestInterceptor feignHeaderPropagator() {
        return new FeignHeaderPropagator();
    }

    /**
     * 全局 Feign 超时（P3.8 R7-3）。
     *
     * <p>Spring Cloud OpenFeign 的 {@code FeignClientsConfiguration} 里同名 Bean 带
     * {@code @ConditionalOnMissingBean}，而该判断默认策略是 {@code SearchStrategy.ALL}（含父上下文）——
     * 因此在这里声明一次，所有服务的 Feign 客户端都会采用本超时，
     * 不必逐服务在 yml 里重复配 {@code spring.cloud.openfeign.client.config.default.*}。</p>
     *
     * <p>要点：<b>不设超时等于读超时 60 秒</b>，对端假死时会连带拖停调用方线程（详见
     * {@link FeignTimeoutProperties} 的说明）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public Request.Options feignRequestOptions(FeignTimeoutProperties properties) {
        return new Request.Options(
                properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS,
                properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS,
                properties.isFollowRedirects());
    }
}
