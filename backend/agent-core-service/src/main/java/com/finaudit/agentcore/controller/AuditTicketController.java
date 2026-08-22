package com.finaudit.agentcore.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.enums.AuditAction;
import com.finaudit.agentcore.pojo.dto.AuditActionRequest;
import com.finaudit.agentcore.pojo.vo.AuditRecordVO;
import com.finaudit.agentcore.pojo.vo.AuditTicketDetailVO;
import com.finaudit.agentcore.pojo.vo.AuditTicketVO;
import com.finaudit.agentcore.service.AuditTicketService;
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

import java.util.List;

@Tag(name = "审批工单", description = "P3b 审批工单闭环：工单列表 / 详情 / 留痕 / 审批动作")
@RestController
@RequestMapping("/api/v1/audit/tickets")
public class AuditTicketController {

    private final AuditTicketService auditTicketService;

    public AuditTicketController(AuditTicketService auditTicketService) {
        this.auditTicketService = auditTicketService;
    }

    @Operation(summary = "工单分页", description = "支持 status / taskId 过滤；非财务角色仅返回本人提交的工单")
    @GetMapping
    public R<Page<AuditTicketVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Long taskId,
                                       @RequestHeader("X-Tenant-Id") Long tenantId,
                                       @RequestHeader("X-User-Id") Long userId,
                                       @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.page(pageNum, pageSize, status, taskId, userId, roles));
    }

    @Operation(summary = "工单详情", description = "工单信息 + 关联报销单详情（GENERIC 任务为 null）+ 留痕时间线")
    @GetMapping("/{id}")
    public R<AuditTicketDetailVO> detail(@PathVariable Long id,
                                         @RequestHeader("X-Tenant-Id") Long tenantId,
                                         @RequestHeader("X-User-Id") Long userId,
                                         @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.detail(id, userId, roles));
    }

    @Operation(summary = "工单留痕", description = "审批动作时间线（append-only，时间升序）")
    @GetMapping("/{id}/records")
    public R<List<AuditRecordVO>> records(@PathVariable Long id,
                                          @RequestHeader("X-Tenant-Id") Long tenantId,
                                          @RequestHeader("X-User-Id") Long userId,
                                          @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.records(id, userId, roles));
    }

    @Operation(summary = "审批通过", description = "任务沿用流水线结果收尾 SUCCESS，报销单 SUCCESS，工单闭合 APPROVED")
    @PostMapping("/{id}/approve")
    public R<AuditTicketVO> approve(@PathVariable Long id,
                                    @RequestBody(required = false) @Valid AuditActionRequest request,
                                    @RequestHeader("X-Tenant-Id") Long tenantId,
                                    @RequestHeader("X-User-Id") Long userId,
                                    @RequestHeader(value = "X-Username", required = false) String username,
                                    @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.action(id, AuditAction.APPROVE, userId, username, roles, request));
    }

    @Operation(summary = "审批驳回", description = "任务置 REJECTED，报销单 FAILED，工单闭合 REJECTED")
    @PostMapping("/{id}/reject")
    public R<AuditTicketVO> reject(@PathVariable Long id,
                                   @RequestBody(required = false) @Valid AuditActionRequest request,
                                   @RequestHeader("X-Tenant-Id") Long tenantId,
                                   @RequestHeader("X-User-Id") Long userId,
                                   @RequestHeader(value = "X-Username", required = false) String username,
                                   @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.action(id, AuditAction.REJECT, userId, username, roles, request));
    }

    @Operation(summary = "终止工单", description = "任务置 REJECTED + 终止原因，报销单 FAILED，工单闭合 TERMINATED")
    @PostMapping("/{id}/terminate")
    public R<AuditTicketVO> terminate(@PathVariable Long id,
                                      @RequestBody(required = false) @Valid AuditActionRequest request,
                                      @RequestHeader("X-Tenant-Id") Long tenantId,
                                      @RequestHeader("X-User-Id") Long userId,
                                      @RequestHeader(value = "X-Username", required = false) String username,
                                      @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.action(id, AuditAction.TERMINATE, userId, username, roles, request));
    }

    @Operation(summary = "同意撤销", description = "提交人已发起撤销申请后，财务同意：任务/报销单作废 CANCELLED + 附件解绑，工单 WITHDRAWN")
    @PostMapping("/{id}/withdraw-agree")
    public R<AuditTicketVO> withdrawAgree(@PathVariable Long id,
                                          @RequestBody(required = false) @Valid AuditActionRequest request,
                                          @RequestHeader("X-Tenant-Id") Long tenantId,
                                          @RequestHeader("X-User-Id") Long userId,
                                          @RequestHeader(value = "X-Username", required = false) String username,
                                          @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.action(id, AuditAction.WITHDRAW_AGREE, userId, username, roles, request));
    }

    @Operation(summary = "拒绝撤销", description = "财务拒绝撤销申请：工单 WITHDRAW_PENDING → APPROVED 原地返回（数据不动）")
    @PostMapping("/{id}/withdraw-refuse")
    public R<AuditTicketVO> withdrawRefuse(@PathVariable Long id,
                                           @RequestBody(required = false) @Valid AuditActionRequest request,
                                           @RequestHeader("X-Tenant-Id") Long tenantId,
                                           @RequestHeader("X-User-Id") Long userId,
                                           @RequestHeader(value = "X-Username", required = false) String username,
                                           @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return R.success(auditTicketService.action(id, AuditAction.WITHDRAW_REFUSE, userId, username, roles, request));
    }
}
