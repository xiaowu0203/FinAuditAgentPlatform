package com.finaudit.toolservice.controller;

import com.finaudit.starter.web.result.R;
import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.toolservice.pojo.dto.ToolExecuteRequest;
import com.finaudit.toolservice.pojo.dto.ToolRegistryRegisterRequest;
import com.finaudit.toolservice.pojo.entity.ToolRegistry;
import com.finaudit.toolservice.service.ToolRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具统一入口（<b>用户侧契约</b>）：单一控制器、统一 {@code /api/v1/tools} 前缀。
 *
 * <p>经网关（9080）+ JWT 鉴权；租户来源为 {@code TenantContextHolder}（网关从 JWT 快照注入
 * {@code X-Tenant-Id}），<b>不再以请求头默认值兜底</b>。</p>
 *
 * <p><b>P3.8 / R0-7 变更</b>：原 {@code GET /api/v1/tools} 同时承担「用户查看工具目录」与
 * 「agent-core 规划器拉取目录（Feign）」两种职责，因此无法挂权限码——挂则内部链路因无用户上下文被 403。
 * 现将内部职责拆到 {@link InternalToolController}（{@code /internal/tools}，网关不暴露），
 * 本类端点得以按 {@code tool:manage} / {@code tool:execute} 正常收口。</p>
 */
@Tag(name = "工具", description = "工具列表 / 注册 / 调试直调")
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistryService registryService;

    public ToolController(ToolRegistryService registryService) {
        this.registryService = registryService;
    }

    @GetMapping
    @RequirePerm("tool:manage")
    @Operation(summary = "工具列表（租户级）", description = "查看本租户工具目录与入参 Schema（操作级权限 tool:manage）")
    @ApiResponse(responseCode = "200", description = "操作成功，body 为 R 包装的 ToolRegistry 列表")
    public R<List<ToolRegistry>> list(@RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        return R.success(registryService.listEnabled(requireTenant(tenantId)));
    }

    @PostMapping
    @RequirePerm("tool:manage")
    @Operation(summary = "工具注册", description = "注册工具，返回工具详情（操作级权限 tool:manage）")
    @ApiResponse(responseCode = "200", description = "操作成功，body 为 R 包装的 ToolRegistry")
    public R<ToolRegistry> register(@Valid @RequestBody ToolRegistryRegisterRequest request,
                                    @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        return R.success(registryService.register(request, requireTenant(tenantId)));
    }

    @PostMapping("/{code}/execute")
    @RequirePerm("tool:execute")
    @Operation(summary = "工具调试直调", description = "调试工具，返回工具执行结果（操作级权限 tool:execute；MQ 主链路直连 service 不受此限）")
    @ApiResponse(responseCode = "200", description = "操作成功，body 为 R 包装的 Map<String, Object>")
    public R<Map<String, Object>> debugExecute(@PathVariable String code,
                                               @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId,
                                               @Valid @RequestBody ToolExecuteRequest request) {
        return R.success(registryService.execute(code, requireTenant(tenantId), request.inputParams()));
    }

    /**
     * 租户上下文缺失即拒绝（fail-closed）。
     * <p>此前三端点均使用 {@code defaultValue="1"}，绕过网关直连服务（9202）时会静默读写默认租户的工具目录；
     * 现改为显式拒绝，与 JWT 快照注入的租户形成唯一来源。</p>
     */
    private Long requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
        return tenantId;
    }
}
