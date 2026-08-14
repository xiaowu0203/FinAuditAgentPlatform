package com.finaudit.agentcore;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Agent 核心服务入口。
 * <p>P1：单 Agent 任务闭环（提交 → 拆解 → 工具联动 → 结果落库），事件驱动 MQ 编排。</p>
 */
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.finaudit.agentcore.mapper")
public class AgentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCoreApplication.class, args);
    }
}
