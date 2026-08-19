package com.finaudit.agentcore.controller;

import com.finaudit.agentcore.config.AdminProperties;
import com.finaudit.agentcore.pojo.dto.RuleSaveRequest;
import com.finaudit.agentcore.pojo.vo.RuleVO;
import com.finaudit.agentcore.service.FinanceRuleService;
import com.finaudit.agentcore.util.FinanceRoles;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "规则配置", description = "财务规则配置管理端点（P2c）")
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final FinanceRuleService financeRuleService;
    private final AdminProperties adminProperties;

    public RuleController(FinanceRuleService financeRuleService, AdminProperties adminProperties) {
        this.financeRuleService = financeRuleService;
        this.adminProperties = adminProperties;
    }

    @Operation(summary = "规则列表", description = "当前租户全部规则（含草稿与已发布，published 标生效状态）")
    @GetMapping
    public R<List<RuleVO>> list(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        // 校验租户ID、管理权限
        requireTenant(tenantId);
        requireAdmin(userId, roles);
        return R.success(financeRuleService.listAll(tenantId).stream().map(RuleVO::from).toList());
    }

    @Operation(summary = "新增规则", description = "初始为草稿（published=0），需发布才生效")
    @PostMapping
    public R<RuleVO> save(@Valid @RequestBody RuleSaveRequest request,
                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        // 校验租户ID、管理权限
        requireTenant(tenantId);
        requireAdmin(userId, roles);
        return R.success(RuleVO.from(financeRuleService.save(request, tenantId)));
    }

    @Operation(summary = "修改规则", description = "变更即草稿，需重新发布才生效")
    @PutMapping("/{id}")
    public R<RuleVO> update(@PathVariable("id") Long id,
                            @Valid @RequestBody RuleSaveRequest request,
                            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                            @RequestHeader(value = "X-User-Id", required = false) Long userId,
                            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        // 校验租户ID、管理权限
        requireTenant(tenantId);
        requireAdmin(userId, roles);
        return R.success(RuleVO.from(financeRuleService.update(id, request, tenantId)));
    }

    @Operation(summary = "启停规则", description = "翻转 enabled；变更即草稿需重新发布")
    @PostMapping("/{id}/toggle")
    public R<RuleVO> toggle(@PathVariable("id") Long id,
                            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                            @RequestHeader(value = "X-User-Id", required = false) Long userId,
                            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        // 校验租户ID、管理权限
        requireTenant(tenantId);
        requireAdmin(userId, roles);
        return R.success(RuleVO.from(financeRuleService.toggle(id, tenantId)));
    }

    @Operation(summary = "发布规则", description = "置目标规则 published=1 + 版本自增，写 Nacos 生效集，改规则不重启即时生效")
    @PostMapping("/{id}/publish")
    public R<RuleVO> publish(@PathVariable("id") Long id,
                             @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                             @RequestHeader(value = "X-User-Id", required = false) Long userId,
                             @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        // 校验租户ID、管理权限
        requireTenant(tenantId);
        requireAdmin(userId, roles);
        return R.success(RuleVO.from(financeRuleService.publish(id, tenantId)));
    }

    /**
     * 校验租户ID
     * @param tenantId 租户ID
     */
    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
    }

    /**
     * 校验管理权限（finance 角色或白名单）
     * @param userId 用户ID
     * @param roles 角色列表
     */
    private void requireAdmin(Long userId, String roles) {
        if (FinanceRoles.isFinance(roles)) {
            return;
        }
        if (userId == null || !adminProperties.getUserIds().contains(userId)) {
            throw new BizException("无规则配置管理权限");
        }
    }
}
