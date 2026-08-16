package com.finaudit.starter.feign;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;

/**
 * Feign 契约自动扫描：本 starter 作为「跨服务同步调用」依赖被引入时才生效，
 * 自动注册 {@code com.finaudit.starter.web.feign} 下的跨服务客户端
 * （AgentCoreServiceFeign / ToolServiceFeign / FileServiceFeign，契约接口定义在 common-code），
 * 各服务无需再单独配置 {@code @EnableFeignClients(basePackages=...)}。
 */
@AutoConfiguration
@ConditionalOnClass(FeignClient.class)
@EnableFeignClients(basePackages = "com.finaudit.starter.web.feign")
public class FeignClientsAutoConfiguration {
}
