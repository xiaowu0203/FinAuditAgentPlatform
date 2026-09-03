package com.finaudit.starter.web.feign;

import com.finaudit.starter.web.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * tenant-service 部门契约（P3.5b）：下游服务（agent-core 等）经此查询 sys_dept。
 * <p>租户经 {@code X-Tenant-Id} 请求头传递，服务间经 Nacos 服务名直连（不经网关）。
 * 仅有内部读端点（部门存在性）；不经此契约触达用户/角色等敏感数据。</p>
 */
@FeignClient(name = "tenant-service")
public interface TenantServiceFeign {

    /**
     * 部门是否存在且启用（agent-core 提交校验 / budget_query 越权校验的 sys_dept 存在性）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param deptId   部门ID
     * @return true=存在且启用；false=不存在/停用/跨租户
     */
    @GetMapping("/api/v1/depts/exists")
    R<Boolean> deptExists(@RequestHeader("X-Tenant-Id") Long tenantId,
                          @RequestParam("deptId") Long deptId);
}