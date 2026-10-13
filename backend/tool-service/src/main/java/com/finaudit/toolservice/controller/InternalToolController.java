package com.finaudit.toolservice.controller;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.pojo.entity.ToolRegistry;
import com.finaudit.toolservice.service.ToolRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具服务<b>内部契约</b>（服务间 Feign 调用专用，P3.8 / R0-7）。
 *
 * <p><b>为什么单独开一个端点</b>：agent-core 的 {@code TaskPlanner} 需要按租户拉取工具目录注入大模型提示词，
 * 而该能力原先复用对外端点 {@code GET /api/v1/tools}。对外端点现挂 {@code tool:manage} 权限码，
 * 内部 Feign 调用没有用户上下文（{@code UserContextHolder} 为空），会被 {@code PermissionInterceptor}
 * fail-closed 拒绝 403——因此必须拆分，而非给内部调用开后门。</p>
 *
 * <p><b>安全边界</b>：前缀 {@code /internal/**} 不在网关路由表内（网关只路由 {@code /api/v1/**}），
 * 外部经网关不可达，故不挂 {@code @RequirePerm}；租户经 {@code X-Tenant-Id} 显式声明（Feign 无法透传
 * MQ 消费线程的请求头，必须显式传参），缺失即拒绝，不落默认租户。</p>
 *
 * <p>⚠️ 修改本类端点时必须同步 {@code common-code} 的 {@code ToolServiceFeign}</p>
 */
@Tag(name = "工具-内部契约", description = "服务间调用（/internal/tools, 网关不暴露）")
@RestController
@RequestMapping("/internal/tools")
public class InternalToolController {

    private final ToolRegistryService registryService;

    public InternalToolController(ToolRegistryService registryService) {
        this.registryService = registryService;
    }

    @Operation(summary = "已启用工具目录（内部）", description = "供 agent-core 规划器按租户拉取工具目录注入大模型")
    @GetMapping
    public R<List<ToolRegistry>> listEnabled(@RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，内部契约不接受无租户调用");
        }
        return R.success(registryService.listEnabled(tenantId));
    }
}
