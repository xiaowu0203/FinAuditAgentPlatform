package com.finaudit.agentcore.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementSubmitRequest;
import com.finaudit.agentcore.pojo.vo.AuditTicketVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementDetailVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementVO;
import com.finaudit.agentcore.service.AgentOrchestrator;
import com.finaudit.agentcore.service.AuditTicketService;
import com.finaudit.agentcore.service.ReimbursementService;
import com.finaudit.agentcore.util.FinanceRoles;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "报销单", description = "报销单提交/审核闭环/提交人修改重跑与撤回撤销")
@RestController
@RequestMapping("/api/v1/reimbursements")
public class ReimbursementController {

    private final ReimbursementService reimbursementService;
    private final AuditTicketService auditTicketService;
    private final AgentOrchestrator orchestrator;

    public ReimbursementController(ReimbursementService reimbursementService,
                                   AuditTicketService auditTicketService,
                                   AgentOrchestrator orchestrator) {
        this.reimbursementService = reimbursementService;
        this.auditTicketService = auditTicketService;
        this.orchestrator = orchestrator;
    }

    @Operation(summary = "提交报销单（生成审核任务）")
    @PostMapping
    public R<ReimbursementVO> submit(@Valid @RequestBody ReimbursementSubmitRequest request,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                     @RequestHeader(value = "X-User-Id", required = false) Long applicantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
        if (applicantId == null) {
            throw new BizException("缺少用户标识 X-User-Id，请通过网关访问");
        }
        return R.success(reimbursementService.submit(request, tenantId, applicantId));
    }

    @Operation(summary = "报销单分页查询", description = "status 为空查全部；非财务角色仅本人，finance 看本租户全量")
    @GetMapping
    public R<Page<ReimbursementVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "10") int pageSize,
                                         @RequestParam(required = false) String status,
                                         @RequestHeader(value = "X-User-Id", required = false) Long applicantId,
                                         @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(reimbursementService.page(pageNum, pageSize, status, applicantId, FinanceRoles.isFinance(roles)));
    }

    @Operation(summary = "报销单详情", description = "含明细 + 附件预签名 URL；非财务角色仅本人，finance 可看全部")
    @GetMapping("/{id}")
    public R<ReimbursementDetailVO> detail(@PathVariable Long id,
                                           @RequestHeader(value = "X-User-Id", required = false) Long applicantId,
                                           @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(reimbursementService.detail(id, applicantId, FinanceRoles.isFinance(roles)));
    }

    @Operation(summary = "修改明细重跑", description = "提交人在待审批/已驳回状态下修改明细（标题/部门不可改），服务端重算总额并回退流水线重跑；工单置 AMENDED、rerun_count+1")
    @PostMapping("/{id}/resubmit")
    public R<Long> resubmit(@PathVariable Long id,
                            @Valid @RequestBody ReimbursementResubmitRequest request,
                            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                            @RequestHeader(value = "X-User-Id", required = false) Long userId,
                            @RequestHeader(value = "X-Username", required = false) String username) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
        if (userId == null) {
            throw new BizException("缺少用户标识 X-User-Id，请通过网关访问");
        }
        Long taskId = auditTicketService.resubmit(id, request, tenantId, userId, username);
        // 推进重跑流程
        orchestrator.continueTask(taskId);
        return R.success(taskId);
    }

    @Operation(summary = "撤回报销单", description = "提交人在待审批状态下撤回：工单 WITHDRAWN，任务/报销单作废 CANCELLED，附件解绑")
    @PostMapping("/{id}/withdraw")
    public R<AuditTicketVO> withdraw(@PathVariable Long id,
                                     @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                     @RequestHeader(value = "X-Username", required = false) String username) {
        if (userId == null) {
            throw new BizException("缺少用户标识 X-User-Id，请通过网关访问");
        }
        return R.success(auditTicketService.withdraw(id, userId, username));
    }

    @Operation(summary = "发起撤销申请", description = "提交人在已通过状态下发起撤销，工单 WITHDRAW_PENDING 等财务同意/拒绝（幂等：已待审直接返回）")
    @PostMapping("/{id}/withdraw-request")
    public R<AuditTicketVO> withdrawRequest(@PathVariable Long id,
                                            @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                            @RequestHeader(value = "X-Username", required = false) String username) {
        if (userId == null) {
            throw new BizException("缺少用户标识 X-User-Id，请通过网关访问");
        }
        return R.success(auditTicketService.requestWithdraw(id, userId, username));
    }
}
