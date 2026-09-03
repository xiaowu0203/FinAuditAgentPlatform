package com.finaudit.agentcore.controller;

import com.finaudit.agentcore.service.AttachmentService;
import com.finaudit.agentcore.service.BudgetService;
import com.finaudit.agentcore.service.FinanceRuleService;
import com.finaudit.agentcore.service.ReimbursementService;
import com.finaudit.starter.web.auth.UserContext;
import com.finaudit.starter.web.auth.UserContextHolder;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.dto.BudgetVO;
import com.finaudit.starter.web.feign.dto.DuplicateCheckVO;
import com.finaudit.starter.web.feign.dto.OcrResultWritebackRequest;
import com.finaudit.starter.web.feign.dto.RuleCheckRequest;
import com.finaudit.starter.web.feign.dto.RuleCheckVO;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "审核数据", description = "工具-facing 审核数据端点（P2b）")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditDataController {

    private final AttachmentService attachmentService;
    private final BudgetService budgetService;
    private final FinanceRuleService financeRuleService;
    private final ReimbursementService reimbursementService;

    public AuditDataController(AttachmentService attachmentService,
                               BudgetService budgetService,
                               FinanceRuleService financeRuleService,
                               ReimbursementService reimbursementService) {
        this.attachmentService = attachmentService;
        this.budgetService = budgetService;
        this.financeRuleService = financeRuleService;
        this.reimbursementService = reimbursementService;
    }

    @Operation(summary = "OCR 结果回写", description = "按 file_record_id 定位附件，回填 ocr_status/file_type/ocr_result")
    @PostMapping("/attachments/{fileRecordId}/ocr-result")
    public R<Void> writebackOcrResult(@PathVariable("fileRecordId") Long fileRecordId,
                                      @RequestBody OcrResultWritebackRequest request,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        attachmentService.updateOcrResult(fileRecordId, request.ocrStatus(), request.fileType(), request.ocrResult());
        return R.success();
    }

    @Operation(summary = "部门预算查询", description = "按部门 + 周期（YYYY-MM）；deptId 优先（P3.5b 权威键），未传回退 deptName（存量）；未配置返回 data=null")
    @GetMapping("/budgets")
    public R<BudgetVO> queryBudget(@RequestParam(value = "deptName", required = false) String deptName,
                                   @RequestParam(value = "deptId", required = false) Long deptId,
                                   @RequestParam("period") String period,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        requireBudgetOrInternal();
        BudgetVO vo = deptId != null
                ? budgetService.findByDeptIdPeriod(tenantId, deptId, period)
                : budgetService.findByDeptPeriod(tenantId, deptName, period);
        return R.success(vo);
    }

    @Operation(summary = "财务规则校验", description = "agent-core 按 finance_rule 评估，返回命中规则 + 是否超标")
    @PostMapping("/rules/check")
    public R<RuleCheckVO> checkRules(@RequestBody RuleCheckRequest request,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(financeRuleService.check(tenantId, request));
    }

    @Operation(summary = "重复报销检测", description = "按申请人 + 金额 + 日期区间 + 商户（OCR 双侧可得时）查历史报销单")
    @GetMapping("/reimbursements/duplicates")
    public R<DuplicateCheckVO> queryDuplicates(@RequestParam("reimbId") Long reimbId,
                                               @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(reimbursementService.queryDuplicates(tenantId, reimbId));
    }

    @Operation(summary = "budget_query 越权校验", description = "P3.5b 收紧：有 reimbId 校验 预算行 dept_id==reimb.dept_id（本人部门语义）；无 reimbId 仅查 sys_dept 存在性")
    @GetMapping("/budgets/allowed")
    public R<Boolean> isBudgetQueryAllowed(@RequestParam(value = "reimbId", required = false) Long reimbId,
                                           @RequestParam(value = "deptId", required = false) Long deptId,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(budgetService.isBudgetQueryAllowed(tenantId, reimbId, deptId));
    }

    @Operation(summary = "报销单归属租户查询", description = "P3c 工具防越权：返回报销单所属租户ID（duplicate_check/ocr_extract 校验 reimbId 归属）")
    @GetMapping("/reimbursements/{reimbId}/tenant")
    public R<Long> findReimbTenantId(@PathVariable("reimbId") Long reimbId,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(reimbursementService.findTenantIdByReimb(reimbId));
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
    }

    /**
     * budget_query 工具路径与预算数据对外读的边界（P3.5c）：
     * 内部链路（MQ 消费 / Feign 无用户上下文）放行——工具经 ToolAccessGuard 完成了部门归属校验；
     * 网关登录用户直呼 → 须持有 budget:viewAll（与预算全部门查询豁免位一致）。
     */
    private void requireBudgetOrInternal() {
        UserContext user = UserContextHolder.get();
        if (user != null && !user.hasPerm("budget:viewAll")) {
            throw new BizException("无预算查询权限");
        }
    }
}
