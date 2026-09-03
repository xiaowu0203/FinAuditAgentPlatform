package com.finaudit.agentcore.controller;

import com.finaudit.agentcore.pojo.dto.RuleSaveRequest;
import com.finaudit.agentcore.pojo.vo.RuleVO;
import com.finaudit.agentcore.service.FinanceRuleService;
import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.auth.UserContext;
import com.finaudit.starter.web.auth.UserContextHolder;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 规则配置接口（P3.5a 起类级 @RequirePerm("rule:manage") 统一收口，
 * 替换 P2 期角色字符串 + 用户 ID 白名单判定；AdminProperties 白名单随之退役）。
 */
@Tag(name = "规则配置", description = "财务规则配置管理端点（P2c）")
@RestController
@RequestMapping("/api/v1/rules")
@RequirePerm("rule:manage")
public class RuleController {

    private final FinanceRuleService financeRuleService;

    public RuleController(FinanceRuleService financeRuleService) {
        this.financeRuleService = financeRuleService;
    }

    @Operation(summary = "规则列表", description = "当前租户全部规则（含草稿与已发布，published 标生效状态）")
    @GetMapping
    public R<List<RuleVO>> list() {
        return R.success(financeRuleService.listAll(requiredTenant()).stream().map(RuleVO::from).toList());
    }

    @Operation(summary = "新增规则", description = "初始为草稿（published=0），需发布才生效")
    @PostMapping
    public R<RuleVO> save(@Valid @RequestBody RuleSaveRequest request) {
        return R.success(RuleVO.from(financeRuleService.save(request, requiredTenant())));
    }

    @Operation(summary = "修改规则", description = "变更即草稿，需重新发布才生效")
    @PutMapping("/{id}")
    public R<RuleVO> update(@PathVariable("id") Long id,
                            @Valid @RequestBody RuleSaveRequest request) {
        return R.success(RuleVO.from(financeRuleService.update(id, request, requiredTenant())));
    }

    @Operation(summary = "启停规则", description = "翻转 enabled；变更即草稿需重新发布")
    @PostMapping("/{id}/toggle")
    public R<RuleVO> toggle(@PathVariable("id") Long id) {
        return R.success(RuleVO.from(financeRuleService.toggle(id, requiredTenant())));
    }

    @Operation(summary = "发布规则", description = "置目标规则 published=1 + 版本自增，写 Nacos 生效集，改规则不重启即时生效")
    @PostMapping("/{id}/publish")
    public R<RuleVO> publish(@PathVariable("id") Long id) {
        return R.success(RuleVO.from(financeRuleService.publish(id, requiredTenant())));
    }

    /**
     * 取当前登录用户上下文（网关注入），缺失即拒绝——规则配置不接受匿名调用。
     */
    private UserContext requiredUser() {
        UserContext user = UserContextHolder.get();
        if (user == null) {
            throw new BizException("缺少登录上下文，请通过网关访问");
        }
        return user;
    }

    /**
     * 取当前租户：权限已由类级 @RequirePerm("rule:manage") 校验，这里只取租户做数据隔离。
     */
    private Long requiredTenant() {
        return requiredUser().getTenantId();
    }
}