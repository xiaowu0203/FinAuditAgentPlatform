package com.finaudit.agentcore.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.enums.AuditAction;
import com.finaudit.agentcore.pojo.dto.AuditActionRequest;
import com.finaudit.agentcore.pojo.vo.AuditRecordVO;
import com.finaudit.agentcore.pojo.vo.AuditTicketDetailVO;
import com.finaudit.agentcore.pojo.vo.AuditTicketVO;
import com.finaudit.agentcore.service.AuditTicketService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "审批工单", description = "P3b 审批工单闭环：工单列表 / 详情 / 留痕 / 审批动作")
@RestController
@RequestMapping("/api/v1/audit/tickets")
public class AuditTicketController {

    private final AuditTicketService auditTicketService;

    public AuditTicketController(AuditTicketService auditTicketService) {
        this.auditTicketService = auditTicketService;
    }

    @Operation(summary = "工单分页", description = "支持 status / taskId 过滤；无 audit:viewAll 权限仅返回本人提交的工单")
    @GetMapping
    public R<Page<AuditTicketVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Long taskId) {
        UserContext user = UserContextHolder.get();
        return R.success(auditTicketService.page(pageNum, pageSize, status, taskId,
                user == null ? null : user.getUserId(),
                UserContextHolder.hasPerm("audit:viewAll")));
    }

    @Operation(summary = "工单详情", description = "工单信息 + 关联报销单详情（GENERIC 任务为 null）+ 留痕时间线；无 audit:viewAll 仅本人可看")
    @GetMapping("/{id}")
    public R<AuditTicketDetailVO> detail(@PathVariable Long id) {
        UserContext user = UserContextHolder.get();
        return R.success(auditTicketService.detail(id,
                user == null ? null : user.getUserId(),
                UserContextHolder.hasPerm("audit:viewAll")));
    }

    @Operation(summary = "工单留痕", description = "审批动作时间线（append-only，时间升序）；无 audit:viewAll 仅本人可看")
    @GetMapping("/{id}/records")
    public R<List<AuditRecordVO>> records(@PathVariable Long id) {
        UserContext user = UserContextHolder.get();
        return R.success(auditTicketService.records(id,
                user == null ? null : user.getUserId(),
                UserContextHolder.hasPerm("audit:viewAll")));
    }

    @Operation(summary = "审批通过", description = "任务沿用流水线结果收尾 SUCCESS，报销单 SUCCESS，工单闭合 APPROVED；无 audit:approve 返回 403")
    @PostMapping("/{id}/approve")
    @RequirePerm("audit:approve")
    public R<AuditTicketVO> approve(@PathVariable Long id,
                                    @RequestBody(required = false) @Valid AuditActionRequest request) {
        UserContext user = requiredUser();
        return R.success(auditTicketService.action(id, AuditAction.APPROVE, user.getUserId(), user.getUsername(), rolesOf(user), request));
    }

    @Operation(summary = "审批驳回", description = "任务置 REJECTED，报销单 FAILED，工单闭合 REJECTED；无 audit:approve 返回 403")
    @PostMapping("/{id}/reject")
    @RequirePerm("audit:approve")
    public R<AuditTicketVO> reject(@PathVariable Long id,
                                   @RequestBody(required = false) @Valid AuditActionRequest request) {
        UserContext user = requiredUser();
        return R.success(auditTicketService.action(id, AuditAction.REJECT, user.getUserId(), user.getUsername(), rolesOf(user), request));
    }

    @Operation(summary = "终止工单", description = "任务置 REJECTED + 终止原因，报销单 FAILED，工单闭合 TERMINATED；无 audit:approve 返回 403")
    @PostMapping("/{id}/terminate")
    @RequirePerm("audit:approve")
    public R<AuditTicketVO> terminate(@PathVariable Long id,
                                      @RequestBody(required = false) @Valid AuditActionRequest request) {
        UserContext user = requiredUser();
        return R.success(auditTicketService.action(id, AuditAction.TERMINATE, user.getUserId(), user.getUsername(), rolesOf(user), request));
    }

    @Operation(summary = "同意撤销", description = "提交人已发起撤销申请后，财务同意：任务/报销单作废 CANCELLED + 附件解绑，工单 WITHDRAWN；无 audit:approve 返回 403")
    @PostMapping("/{id}/withdraw-agree")
    @RequirePerm("audit:approve")
    public R<AuditTicketVO> withdrawAgree(@PathVariable Long id,
                                          @RequestBody(required = false) @Valid AuditActionRequest request) {
        UserContext user = requiredUser();
        return R.success(auditTicketService.action(id, AuditAction.WITHDRAW_AGREE, user.getUserId(), user.getUsername(), rolesOf(user), request));
    }

    @Operation(summary = "拒绝撤销", description = "财务拒绝撤销申请：工单 WITHDRAW_PENDING → APPROVED 原地返回（数据不动）；无 audit:approve 返回 403")
    @PostMapping("/{id}/withdraw-refuse")
    @RequirePerm("audit:approve")
    public R<AuditTicketVO> withdrawRefuse(@PathVariable Long id,
                                           @RequestBody(required = false) @Valid AuditActionRequest request) {
        UserContext user = requiredUser();
        return R.success(auditTicketService.action(id, AuditAction.WITHDRAW_REFUSE, user.getUserId(), user.getUsername(), rolesOf(user), request));
    }

    /** 审批动作必须存在登录上下文（网关注入）；无上下文由 @RequirePerm 兜底 403。 */
    private UserContext requiredUser() {
        UserContext user = UserContextHolder.get();
        if (user == null) {
            throw new BizException("缺少登录上下文，请通过网关访问");
        }
        return user;
    }

    /** 操作人角色 CSV（快照/JWT 降级源），落审计留痕用；无角色返回 null。 */
    private String rolesOf(UserContext user) {
        return user.getRoles() == null ? null : String.join(",", user.getRoles());
    }
}
