package com.finaudit.tenant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.result.R;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.tenant.pojo.dto.UserCreateRequest;
import com.finaudit.tenant.pojo.dto.UserRoleAssignRequest;
import com.finaudit.tenant.pojo.dto.UserUpdateRequest;
import com.finaudit.tenant.pojo.vo.UserDetailVO;
import com.finaudit.tenant.pojo.vo.UserVO;
import com.finaudit.tenant.service.SysUserService;
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
 * 用户管理接口（P3.5a 起按操作级权限标识符收口——原实现仅登录即可调，存在提权风险）。
 * <p>所属租户取请求上下文（网关注入 X-Tenant-Id）。</p>
 */
@Tag(name = "用户管理", description = "用户增删改查 / 角色绑定")
@RestController
@RequestMapping("/api/v1/users")
public class SysUserController {

    private final SysUserService userService;

    public SysUserController(SysUserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "新增用户", description = "密码 BCrypt 落库，可同时绑定角色")
    @PostMapping
    @RequirePerm("user:create")
    public R<UserVO> create(@Valid @RequestBody UserCreateRequest request) {
        return R.success(userService.create(request, TenantContextHolder.getTenantIdOrDefault()));
    }

    @Operation(summary = "更新用户", description = "password 非空才重置密码")
    @PutMapping("/{id}")
    @RequirePerm("user:update")
    public R<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return R.success(userService.update(id, request, TenantContextHolder.getTenantIdOrDefault()));
    }

    @Operation(summary = "绑定角色", description = "替换式：以传入列表为准，空列表即清空；在线用户权限即时生效")
    @PutMapping("/{id}/roles")
    @RequirePerm("user:assign-role")
    public R<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody UserRoleAssignRequest request) {
        userService.assignRoles(id, TenantContextHolder.getTenantIdOrDefault(), request.roleIds());
        return R.success();
    }

    @Operation(summary = "删除用户", description = "逻辑删除 + 踢下全部会话")
    @DeleteMapping("/{id}")
    @RequirePerm("user:delete")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.success();
    }

    @Operation(summary = "用户详情", description = "返回用户信息与角色列表")
    @GetMapping("/{id}")
    @RequirePerm("user:list")
    public R<UserDetailVO> detail(@PathVariable Long id) {
        return R.success(userService.detail(id));
    }

    @Operation(summary = "用户分页查询", description = "按租户上下文隔离 + 关键字过滤")
    @GetMapping
    @RequirePerm("user:list")
    public R<Page<UserVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                @RequestParam(defaultValue = "10") int pageSize,
                                @RequestParam(required = false) String keyword) {
        return R.success(userService.page(pageNum, pageSize, keyword));
    }
}
