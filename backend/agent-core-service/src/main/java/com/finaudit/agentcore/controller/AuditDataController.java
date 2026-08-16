package com.finaudit.agentcore.controller;

import com.finaudit.agentcore.service.AttachmentService;
import com.finaudit.agentcore.service.BudgetService;
import com.finaudit.agentcore.service.FinanceRuleService;
import com.finaudit.agentcore.service.ReimbursementService;
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

/**
 * 审核数据接口（P2b 工具-facing 端点，/api/v1/audit/**）。
 * <p>复用同一批对外接口（Feign 直连，不拆 /internal）：OCR 结果回写 / 预算查询 / 规则校验 / 重复检测。
 * 安全注记：这些端点按租户隔离但未按用户收窄（工具用 reimbId 直取），P2 可接受，P5 加角色校验。</p>
 */
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

    @Operation(summary = "部门预算查询", description = "按部门 + 周期（YYYY-MM）；未配置返回 data=null")
    @GetMapping("/budgets")
    public R<BudgetVO> queryBudget(@RequestParam("deptName") String deptName,
                                   @RequestParam("period") String period,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(budgetService.findByDeptPeriod(tenantId, deptName, period));
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

    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
    }
}
