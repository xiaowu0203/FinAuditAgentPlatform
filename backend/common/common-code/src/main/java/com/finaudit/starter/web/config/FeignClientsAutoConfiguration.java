package com.finaudit.starter.web.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;

/**
 * common-code 内置 Feign 契约自动扫描：任何依赖本模块的服务自动注册
 * {@code com.finaudit.starter.web.feign} 下的跨服务客户端（如 ToolServiceFeign），
 * 各服务无需再单独配置 {@code @EnableFeignClients(basePackages=...)}。
 */
@AutoConfiguration
@ConditionalOnClass(FeignClient.class)
@EnableFeignClients(basePackages = "com.finaudit.starter.web.feign")
public class FeignClientsAutoConfiguration {
}
