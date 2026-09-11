package com.finaudit.starter.web.feign;

import com.finaudit.starter.web.feign.dto.ToolInfo;
import com.finaudit.starter.web.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * tool-service 工具目录契约（跨服务 Feign 客户端，统一放 common-code 供各消费方复用）。
 * <p>租户经 {@code X-Tenant-Id} 请求头传递，服务间经 Nacos 服务名直连（不经网关）。</p>
 *
 * <p><b>P3.8 / R0-7：本契约指向内部端点 {@code /internal/tools}</b>，不再复用对外端点 {@code /api/v1/tools}。
 * 对外端点已挂 {@code tool:manage} 权限码（原先是任意登录用户可读全量工具目录含入参 Schema 的越权面），
 * 而内部 Feign 调用无用户上下文，会被 {@code PermissionInterceptor} fail-closed 拒绝，故必须拆分。</p>
 *
 * <p>⚠️ 与 tool-service 的 {@code InternalToolController} 成对修改</p>
 */
@FeignClient(name = "tool-service")
public interface ToolServiceFeign {

    /**
     * 拉取指定租户已启用工具目录（供 Agent 规划器注入大模型）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @return 工具目录元数据（消费方投影）
     */
    @GetMapping("/internal/tools")
    R<List<ToolInfo>> listEnabled(@RequestHeader("X-Tenant-Id") Long tenantId);
}
