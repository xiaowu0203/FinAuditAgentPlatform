package com.finaudit.starter.web.feign;

import com.finaudit.starter.web.feign.dto.ToolInfo;
import com.finaudit.starter.web.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * tool-service 工具目录契约（跨服务 Feign 客户端，统一放 common-code 供各消费方复用）。
 * <p>复用对外接口 {@code GET /api/v1/tools}：服务端实际返回更全的 ToolRegistry 元数据，
 * 消费方投影为 ToolInfo 所需字段（多余字段由 Jackson 忽略）。租户经 {@code X-Tenant-Id} 请求头传递，
 * 服务间经 Nacos 服务名直连（不经网关）；如需请求头/token 透传能力，消费方需引入 common-feign-starter。</p>
 */
@FeignClient(name = "tool-service")
public interface ToolServiceFeign {

    /**
     * 拉取指定租户已启用工具目录（供 Agent 规划器注入大模型）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @return 工具目录元数据（消费方投影）
     */
    @GetMapping("/api/v1/tools")
    R<List<ToolInfo>> listEnabled(@RequestHeader("X-Tenant-Id") Long tenantId);
}
