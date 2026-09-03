package com.finaudit.tenant.controller;

import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.result.R;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.tenant.pojo.dto.DeptCreateRequest;
import com.finaudit.tenant.pojo.dto.DeptUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysDept;
import com.finaudit.tenant.pojo.vo.DeptVO;
import com.finaudit.tenant.service.SysDeptService;
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

import java.util.List;

@Tag(name = "部门管理", description = "部门树：查询/新增/编辑/删除（写操作需 dept:* 权限码）")
@RestController
@RequestMapping("/api/v1/depts")
public class SysDeptController {

    private final SysDeptService deptService;

    public SysDeptController(SysDeptService deptService) {
        this.deptService = deptService;
    }

    @Operation(summary = "部门树", description = "全量部门树（含停用；报销单创建页部门选择器公用）")
    @GetMapping
    public R<List<DeptVO>> tree() {
        return R.success(deptService.listTree());
    }

    @Operation(summary = "部门是否存在且启用", description = "内部读（agent-core 越权校验/提交校验用），仅登录+租户隔离")
    @GetMapping("/exists")
    public R<Boolean> exists(@RequestParam Long deptId) {
        return R.success(deptService.deptExists(deptId));
    }

    @Operation(summary = "新增部门", description = "父部门（非根）须存在；租户内部门名唯一")
    @PostMapping
    @RequirePerm("dept:create")
    public R<SysDept> create(@Valid @RequestBody DeptCreateRequest request) {
        Long tenantId = TenantContextHolder.getTenantIdOrDefault();
        return R.success(deptService.create(request, tenantId));
    }

    @Operation(summary = "编辑部门", description = "改名/换父/停用；parent 变更做防环校验")
    @PutMapping("/{id}")
    @RequirePerm("dept:update")
    public R<SysDept> update(@PathVariable Long id, @Valid @RequestBody DeptUpdateRequest request) {
        return R.success(deptService.update(id, request));
    }

    @Operation(summary = "删除部门", description = "有子部门或用户引用时拒绝删除")
    @DeleteMapping("/{id}")
    @RequirePerm("dept:delete")
    public R<Void> delete(@PathVariable Long id) {
        deptService.delete(id);
        return R.success();
    }
}