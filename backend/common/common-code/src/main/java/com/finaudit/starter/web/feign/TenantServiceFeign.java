package com.finaudit.starter.web.feign;

import com.finaudit.starter.web.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * tenant-service 契约（P3.5b 部门 / P3.8 R8-2 收件人解析）：下游服务（agent-core 等）经此查询租户侧数据。
 * <p>租户经 {@code X-Tenant-Id} 请求头传递，服务间经 Nacos 服务名直连（不经网关）。
 * 仅有内部读端点（部门存在性、按权限码取用户 id），不经此契约触达用户明细/凭据等敏感数据。</p>
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

    /**
     * 按权限码解析收件人（P3.8 R8-2）：本租户内「启用且未删除、其角色持有该权限码」的用户 id。
     *
     * <p>通知触发方是后台线程（MQ 消费 / 任务收尾 / 定时投递），没有登录用户上下文，
     * 无法从 {@code UserContextHolder} 得知"谁有审批权限"，只能按权限码反查。</p>
     *
     * <p>⚠️ 调用方必须把本调用视为**可能失败的外部依赖**：查不到收件人就少发一条提醒，
     * 绝不能因为通知解析失败把业务流程（工单审批、任务收尾）拖死。</p>
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param permCode 权限码（如 {@code audit:approve}）
     * @return 用户 id 列表（可能为空）
     */
    @GetMapping("/internal/users/by-perm")
    R<List<Long>> listUserIdsByPerm(@RequestHeader("X-Tenant-Id") Long tenantId,
                                    @RequestParam("permCode") String permCode);
}