package com.finaudit.toolservice.controller;

import com.finaudit.starter.web.result.R;
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
 * 工具统一入口：单一控制器、统一 {@code /api/v1/tools} 前缀。
 * <p>对外经网关 + JWT 鉴权；服务间 Feign 调用复用同一批接口（如 {@code GET /api/v1/tools}），
 * 走 Nacos 服务名直连，租户经 {@code X-Tenant-Id} 请求头显式传递（对外由网关注入，对内由调用方声明）。</p>
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
    @Operation(summary = "工具列表", description = "按租户ID查询工具列表（对外/对内共用）")
    @ApiResponse(responseCode = "200", description = "操作成功，body 为 R 包装的 ToolRegistry 列表")
    public R<List<ToolRegistry>> list(@RequestHeader(name = "X-Tenant-Id", defaultValue = "1") Long tenantId) {
        return R.success(registryService.listEnabled(tenantId));
    }

    @PostMapping
    @Operation(summary = "工具注册", description = "注册工具，返回工具详情")
    @ApiResponse(responseCode = "200", description = "操作成功，body 为 R 包装的 ToolRegistry")
    public R<ToolRegistry> register(@Valid @RequestBody ToolRegistryRegisterRequest request,
                                    @RequestHeader(name = "X-Tenant-Id", defaultValue = "1") Long tenantId) {
        return R.success(registryService.register(request, tenantId));
    }

    @PostMapping("/{code}/execute")
    @Operation(summary = "工具调试直调", description = "调试工具，返回工具执行结果")
    @ApiResponse(responseCode = "200", description = "操作成功，body 为 R 包装的 Map<String, Object>")
    public R<Map<String, Object>> debugExecute(@PathVariable String code,
                                               @RequestHeader(name = "X-Tenant-Id", defaultValue = "1") Long tenantId,
                                               @Valid @RequestBody ToolExecuteRequest request) {
        return R.success(registryService.execute(code, tenantId, request.inputParams()));
    }
}
