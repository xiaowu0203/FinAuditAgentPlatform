package com.finaudit.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.result.R;
import com.finaudit.tenant.pojo.dto.TenantCreateRequest;
import com.finaudit.tenant.pojo.dto.TenantUpdateRequest;
import com.finaudit.tenant.pojo.vo.TenantVO;
import com.finaudit.tenant.service.SysTenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理接口（P3.5a 起类级 @RequirePerm 收口——原实现仅登录即可调）。
 * <p>sys_tenant 为全局表（多租户拦截器 ignore），CRUD 不受租户上下文过滤；
 * 类级注解对全部端点生效（本 Controller 无方法级差异化权限）。</p>
 */
@Tag(name = "租户管理", description = "租户增删改查")
@RestController
@RequestMapping("/api/v1/tenants")
@RequirePerm("tenant:manage")
public class SysTenantController {

    private final SysTenantService tenantService;

    public SysTenantController(SysTenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(summary = "新增租户", description = "tenantCode 全局唯一")
    @PostMapping
    public R<TenantVO> create(@Valid @RequestBody TenantCreateRequest request) {
        return R.success(tenantService.create(request));
    }

    @Operation(summary = "更新租户")
    @PutMapping("/{id}")
    public R<TenantVO> update(@PathVariable Long id, @Valid @RequestBody TenantUpdateRequest request) {
        return R.success(tenantService.update(id, request));
    }

    @Operation(summary = "删除租户", description = "逻辑删除")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        tenantService.delete(id);
        return R.success();
    }

    @Operation(summary = "租户详情")
    @GetMapping("/{id}")
    public R<TenantVO> get(@PathVariable Long id) {
        return R.success(tenantService.get(id));
    }

    @Operation(summary = "租户分页查询", description = "按编码/名称关键字过滤")
    @GetMapping
    public R<Page<TenantVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                  @RequestParam(defaultValue = "10") int pageSize,
                                  @RequestParam(required = false) String keyword) {
        return R.success(tenantService.page(pageNum, pageSize, keyword));
    }
}
