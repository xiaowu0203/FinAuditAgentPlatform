package com.finaudit.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关入口。
 * <p>P0：服务注册 Nacos。P1 起：统一鉴权、限流、路由、跨域。</p>
 */
@SpringBootApplication
public class AgentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentGatewayApplication.class, args);
    }
}
