package com.finaudit.toolservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工具执行服务入口。
 * <p>承载内置工具的注册、分发与安全执行：{@code ToolCode} 枚举（6 个内置工具）+ {@code ToolExecutor}
 * 实现按编码分发，{@link com.finaudit.toolservice.service.ToolRegistryService} 统一负责注册、
 * 入参/出参 JSON Schema 校验与执行；{@link com.finaudit.toolservice.service.ToolAccessGuard}
 * 在分发前做跨租户/部门/单据归属防越权校验。</p>
 * <p>对外经 {@link com.finaudit.toolservice.controller.ToolController}（{@code /api/v1/tools}
 * 列表/注册/调试直调，挂 {@code @RequirePerm}），对内走
 * {@link com.finaudit.toolservice.controller.InternalToolController}（{@code /internal/tools}，
 * 网关不暴露）；主链路为 MQ 消费 {@code tool.execute} → 执行 → 落执行日志 → 发布 {@code tool.result}，
 * 其中带租户前缀的 Redis 结果缓存由 {@code ToolExecutionService} 负责（仅 cacheable=1 的工具）。</p>
 */
@SpringBootApplication
@MapperScan("com.finaudit.toolservice.mapper")
public class ToolServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolServiceApplication.class, args);
    }
}
