package com.finaudit.tenant.controller;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import com.finaudit.tenant.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户服务<b>内部契约</b>（服务间 Feign 调用专用，P3.8 R8-2）。
 *
 * <p><b>为什么需要它</b>：agent-core 的主动通知要在「任务转人工 / 撤销请求 / DLQ 告警」时通知
 * <b>有审批权限的人</b>，而这类触发发生在后台线程（MQ 消费、任务收尾、定时投递），
 * 没有 {@code UserContextHolder}，无法知道收件人是谁。只能按权限码反查用户 id。</p>
 *
 * <p><b>为什么不能复用对外端点</b>：对外 {@code GET /api/v1/users} 挂 {@code user:list} 权限码，
 * 且返回的是用户明细（用户名/手机号/部门）。内部调用没有用户上下文，会被
 * {@code PermissionInterceptor} fail-closed 拒绝；即使能过，把"按权限码取 id 列表"开放到对外
 * 等于给任意登录用户一个枚举管理员的接口。故拆分出最小能力的内部端点。</p>
 *
 * <p><b>安全边界</b>：前缀 {@code /internal/**} 不在网关路由表内（网关只路由 {@code /api/v1/**}），
 * 外部经网关不可达，故不挂 {@code @RequirePerm}；租户经 {@code X-Tenant-Id} 显式声明
 * （Feign 调用方在后台线程里没有请求头可透传，必须显式传参），缺失即拒绝，不落默认租户。</p>
 *
 * <p>⚠️ 修改本类端点时必须同步 {@code common-code} 的 {@code TenantServiceFeign}</p>
 */
@Tag(name = "租户-内部契约", description = "服务间调用（/internal/users, 网关不暴露）")
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final SysUserService userService;

    public InternalUserController(SysUserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "按权限码查用户ID（内部）",
            description = "供 agent-core 解析通知收件人（如 audit:approve → 有审批权限的人）；只返回 id 列表，不回用户明细")
    @GetMapping("/by-perm")
    public R<List<Long>> listUserIdsByPerm(@RequestParam("permCode") String permCode,
                                           @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，内部契约不接受无租户调用");
        }
        return R.success(userService.listEnabledUserIdsByPermCode(permCode));
    }
}
