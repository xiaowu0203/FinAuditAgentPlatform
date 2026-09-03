package com.finaudit.tenant.controller;

import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.result.R;
import com.finaudit.tenant.pojo.vo.PermissionVO;
import com.finaudit.tenant.service.SysPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限接口（P3.5a 权限标识符体系）：权限目录查询（角色分配界面勾选树）。
 * <p>权限目录为平台级种子数据（代码即目录，运行期不增删），无管理端点；
 * 角色权限的查看/分配见 {@code SysRoleController}（/api/v1/roles/{id}/permissions）。</p>
 */
@Tag(name = "权限管理", description = "权限目录查询")
@RestController
@RequestMapping("/api/v1/permissions")
@RequirePerm("role:assign-perm")
public class SysPermissionController {

    private final SysPermissionService permissionService;

    public SysPermissionController(SysPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Operation(summary = "权限目录", description = "启用权限按分组+ID 排序（角色分配界面勾选树）")
    @GetMapping
    public R<List<PermissionVO>> catalog() {
        return R.success(permissionService.listCatalog().stream().map(PermissionVO::from).toList());
    }
}
