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
 * 审核数据<b>内部契约</b>（tool-service 工具执行专用，P3.8 / R0-6）。
 *
 * <p><b>为什么从 {@code /api/v1/audit/**} 迁到 {@code /internal/audit/**}</b>：
 * 这些端点是给工具链路用的（OCR 结果回写、预算查询、规则校验、重复检测、越权校验支撑），
 * 原先挂在 {@code /api/v1/audit/**} 且被网关 {@code Path=/api/v1/audit/**} 路由暴露，
 * 而它们的守卫只有「{@code X-Tenant-Id} 非空」——<b>任意登录用户即可调用，其中
 * {@code POST /attachments/{id}/ocr-result} 是写操作，可覆盖本租户任意附件的 OCR 结果，
 * 直接篡改审核结论依据</b>；{@code GET /reimbursements/{id}/tenant} 亦可跨租户探测单据存在性。</p>
 *
 * <p><b>安全边界</b>：前缀 {@code /internal/**} <b>不在网关路由表内</b>（网关只路由 {@code /api/v1/**}），
 * 外部请求经网关不可达，故无需 {@code @RequirePerm}（无用户上下文，权限码也不适用）。
 * 租户隔离由 {@code TenantIdFilter} + MyBatis-Plus 多租户拦截器强制；
 * 业务级越权由 {@code ToolAccessGuard} 在 tool-service 侧先行校验。</p>
 *
 * <p>⚠️ 修改本类端点时必须同步 {@code common-code} 的 {@code AgentCoreServiceFeign}</p>
 */
@Tag(name = "审核数据-内部契约", description = "工具调用（/internal/audit/**, 网关不暴露）")
@RestController
@RequestMapping("/internal/audit")
public class InternalAuditDataController {

    private final AttachmentService attachmentService;
    private final BudgetService budgetService;
    private final FinanceRuleService financeRuleService;
    private final ReimbursementService reimbursementService;

    public InternalAuditDataController(AttachmentService attachmentService,
                                       BudgetService budgetService,
                                       FinanceRuleService financeRuleService,
                                       ReimbursementService reimbursementService) {
        this.attachmentService = attachmentService;
        this.budgetService = budgetService;
        this.financeRuleService = financeRuleService;
        this.reimbursementService = reimbursementService;
    }

    @Operation(summary = "OCR 结果回写（内部）", description = "按 file_record_id 定位附件，回填 ocr_status/file_type/ocr_result")
    @PostMapping("/attachments/{fileRecordId}/ocr-result")
    public R<Void> writebackOcrResult(@PathVariable("fileRecordId") Long fileRecordId,
                                      @RequestBody OcrResultWritebackRequest request,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        attachmentService.updateOcrResult(fileRecordId, request.ocrStatus(), request.fileType(), request.ocrResult());
        return R.success();
    }

    @Operation(summary = "部门预算查询（内部）", description = "按部门 + 周期（YYYY-MM）；deptId 优先（P3.5b 权威键），未传回退 deptName（存量）；未配置返回 data=null")
    @GetMapping("/budgets")
    public R<BudgetVO> queryBudget(@RequestParam(value = "deptName", required = false) String deptName,
                                   @RequestParam(value = "deptId", required = false) Long deptId,
                                   @RequestParam("period") String period,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        BudgetVO vo = deptId != null
                ? budgetService.findByDeptIdPeriod(tenantId, deptId, period)
                : budgetService.findByDeptPeriod(tenantId, deptName, period);
        return R.success(vo);
    }

    @Operation(summary = "财务规则校验（内部）", description = "agent-core 按 finance_rule 评估，返回命中规则 + 是否超标")
    @PostMapping("/rules/check")
    public R<RuleCheckVO> checkRules(@RequestBody RuleCheckRequest request,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(financeRuleService.check(tenantId, request));
    }

    @Operation(summary = "重复报销检测（内部）", description = "按申请人 + 金额 + 日期区间 + 商户（OCR 双侧可得时）查历史报销单")
    @GetMapping("/reimbursements/duplicates")
    public R<DuplicateCheckVO> queryDuplicates(@RequestParam("reimbId") Long reimbId,
                                               @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(reimbursementService.queryDuplicates(tenantId, reimbId));
    }

    @Operation(summary = "budget_query 越权校验（内部）", description = "P3.5b 收紧：有 reimbId 校验 预算行 dept_id==reimb.dept_id（本人部门语义）；无 reimbId 仅查 sys_dept 存在性")
    @GetMapping("/budgets/allowed")
    public R<Boolean> isBudgetQueryAllowed(@RequestParam(value = "reimbId", required = false) Long reimbId,
                                           @RequestParam(value = "deptId", required = false) Long deptId,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(budgetService.isBudgetQueryAllowed(tenantId, reimbId, deptId));
    }

    @Operation(summary = "报销单归属租户查询（内部）", description = "P3c 工具防越权：返回报销单所属租户ID（duplicate_check/ocr_extract 校验 reimbId 归属）")
    @GetMapping("/reimbursements/{reimbId}/tenant")
    public R<Long> findReimbTenantId(@PathVariable("reimbId") Long reimbId,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(reimbursementService.findTenantIdByReimb(reimbId));
    }

    /**
     * 租户上下文缺失即拒绝（fail-closed）。
     * <p>内网调用同样强制租户声明，避免多租户拦截器回退默认租户 1 造成串租户；
     * 不再使用 {@code defaultValue="1"} 这类静默兜底。</p>
     * <p><b>边界备忘</b>：早期对外端点 {@code /api/v1/audit/budgets} 要求登录用户持有
     * {@code budget:viewAll}；该端点已随本类迁入 {@code /internal/**}（网关不可达），
     * 故不再需要用户权限码判定。若后续要向用户开放预算查询，应新建用户侧端点并挂该权限码。</p>
     */
    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，内部契约不接受无租户调用");
        }
    }
}
