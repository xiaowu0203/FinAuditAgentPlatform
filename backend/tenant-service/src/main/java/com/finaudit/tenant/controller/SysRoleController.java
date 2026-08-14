package com.finaudit.tenant.controller;

import com.finaudit.starter.web.result.R;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.tenant.pojo.dto.RoleCreateRequest;
import com.finaudit.tenant.pojo.dto.RoleUpdateRequest;
import com.finaudit.tenant.pojo.vo.RoleVO;
import com.finaudit.tenant.service.SysRoleService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理接口。
 * <p>所属租户取请求上下文（网关注入 X-Tenant-Id），不信任请求体。</p>
 */
@Tag(name = "角色管理", description = "角色增删改查")
@RestController
@RequestMapping("/api/v1/roles")
public class SysRoleController {

    private final SysRoleService roleService;

    public SysRoleController(SysRoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "新增角色", description = "同租户下 roleCode 唯一")
    @PostMapping
    public R<RoleVO> create(@Valid @RequestBody RoleCreateRequest request) {
        return R.success(roleService.create(request, TenantContextHolder.getTenantIdOrDefault()));
    }

    @Operation(summary = "更新角色")
    @PutMapping("/{id}")
    public R<RoleVO> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateRequest request) {
        return R.success(roleService.update(id, request));
    }

    @Operation(summary = "删除角色", description = "逻辑删除")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return R.success();
    }

    @Operation(summary = "角色详情")
    @GetMapping("/{id}")
    public R<RoleVO> get(@PathVariable Long id) {
        return R.success(roleService.get(id));
    }

    @Operation(summary = "角色列表", description = "当前租户全部角色（角色选择器用）")
    @GetMapping
    public R<List<RoleVO>> list() {
        return R.success(roleService.listByTenant(TenantContextHolder.getTenantIdOrDefault()));
    }
}
