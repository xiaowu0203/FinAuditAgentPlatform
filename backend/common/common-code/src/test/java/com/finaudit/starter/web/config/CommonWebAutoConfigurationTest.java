package com.finaudit.starter.web.config;

import com.finaudit.starter.web.auth.PermissionInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommonWebAutoConfiguration} 自动装配回归测试（P3.5 R1 联调缺陷固化）：
 * <p>缺陷：拦截器注册器曾用 {@code @ConditionalOnMissingBean(WebMvcConfigurer.class)}——
 * springdoc（common-swagger-starter）注册 {@link WebMvcConfigurer}（OpenApiWebMvcConfigurer）
 * 后条件必假，{@code PermissionInterceptor} 被静默跳过，@RequirePerm 全线失效（200 直通）。
 * 修复：拦截器按自身类型条件成 bean，注册器恒注册。</p>
 */
class CommonWebAutoConfigurationTest {

    /** 模拟 springdoc 注册的 WebMvcConfigurer（此前导致拦截器被跳过的直接诱因）。 */
    @Configuration(proxyBeanMethods = false)
    static class CompetingWebMvcConfigurerConfig {
        @Bean
        WebMvcConfigurer openApiWebMvcConfigurer() {
            return new WebMvcConfigurer() {
            };
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class));

    @Test
    void interceptorBeanExists_evenWithCompetingWebMvcConfigurer() {
        // 回归：即使存在其它 WebMvcConfigurer，PermissionInterceptor 仍必须注册
        runner.withUserConfiguration(CompetingWebMvcConfigurerConfig.class)
                .run(context -> assertThat(context).hasSingleBean(PermissionInterceptor.class));
    }

    @Test
    void interceptorBeanExists_withoutCompetingWebMvcConfigurer() {
        // 正常场景同样注册
        runner.run(context -> assertThat(context).hasSingleBean(PermissionInterceptor.class));
    }

    @Test
    void userContextFilterBeanRegistered() {
        runner.run(context -> assertThat(context).hasSingleBean(com.finaudit.starter.web.auth.UserContextFilter.class));
    }
}