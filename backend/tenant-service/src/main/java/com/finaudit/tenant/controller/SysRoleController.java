package com.finaudit.tenant.controller;

import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.result.R;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.tenant.pojo.dto.RoleCreateRequest;
import com.finaudit.tenant.pojo.dto.RolePermAssignRequest;
import com.finaudit.tenant.pojo.dto.RoleUpdateRequest;
import com.finaudit.tenant.pojo.vo.RoleVO;
import com.finaudit.tenant.service.SysPermissionService;
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

@Tag(name = "角色管理", description = "角色增删改查 / 角色权限分配")
@RestController
@RequestMapping("/api/v1/roles")
public class SysRoleController {

    private final SysRoleService roleService;
    private final SysPermissionService permissionService;

    public SysRoleController(SysRoleService roleService, SysPermissionService permissionService) {
        this.roleService = roleService;
        this.permissionService = permissionService;
    }

    @Operation(summary = "新增角色", description = "同租户下 roleCode 唯一")
    @PostMapping
    @RequirePerm("role:create")
    public R<RoleVO> create(@Valid @RequestBody RoleCreateRequest request) {
        return R.success(roleService.create(request, TenantContextHolder.getTenantIdOrDefault()));
    }

    @Operation(summary = "更新角色")
    @PutMapping("/{id}")
    @RequirePerm("role:update")
    public R<RoleVO> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateRequest request) {
        return R.success(roleService.update(id, request));
    }

    @Operation(summary = "删除角色", description = "同步清理用户-角色/角色-权限映射并刷新在线用户权限")
    @DeleteMapping("/{id}")
    @RequirePerm("role:delete")
    public R<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return R.success();
    }

    @Operation(summary = "角色详情")
    @GetMapping("/{id}")
    @RequirePerm("role:list")
    public R<RoleVO> get(@PathVariable Long id) {
        return R.success(roleService.get(id));
    }

    @Operation(summary = "角色列表", description = "当前租户全部角色（角色选择器用）")
    @GetMapping
    @RequirePerm("role:list")
    public R<List<RoleVO>> list() {
        return R.success(roleService.listByTenant(TenantContextHolder.getTenantIdOrDefault()));
    }

    @Operation(summary = "角色已分配权限", description = "回显角色当前权限 ID 列表（P3.5a）")
    @GetMapping("/{id}/permissions")
    @RequirePerm("role:assign-perm")
    public R<List<Long>> rolePerms(@PathVariable Long id) {
        roleService.getRequired(id);
        return R.success(permissionService.listPermIdsByRole(id));
    }

    @Operation(summary = "分配角色权限", description = "替换式：以传入列表为准，空列表即清空；在线用户即时生效")
    @PutMapping("/{id}/permissions")
    @RequirePerm("role:assign-perm")
    public R<Void> assignPerms(@PathVariable Long id, @Valid @RequestBody RolePermAssignRequest request) {
        roleService.getRequired(id);
        permissionService.replaceRolePermissions(id, TenantContextHolder.getTenantIdOrDefault(), request.permIds());
        return R.success();
    }
}
