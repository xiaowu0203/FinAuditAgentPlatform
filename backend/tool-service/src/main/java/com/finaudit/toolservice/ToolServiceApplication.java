package com.finaudit.toolservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工具执行服务入口。
 * <p>P1：工具注册表 + 执行器（金额核验 AmountVerifyTool）+ 执行日志，经 MQ 供 agent-core 联动。</p>
 */
@SpringBootApplication
@MapperScan("com.finaudit.toolservice.mapper")
public class ToolServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolServiceApplication.class, args);
    }
}
