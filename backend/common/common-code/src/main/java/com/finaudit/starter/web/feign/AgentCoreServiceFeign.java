package com.finaudit.starter.web.feign;

import com.finaudit.starter.web.feign.dto.BudgetVO;
import com.finaudit.starter.web.feign.dto.DuplicateCheckVO;
import com.finaudit.starter.web.feign.dto.InvoiceMatchRequest;
import com.finaudit.starter.web.feign.dto.InvoiceMatchVO;
import com.finaudit.starter.web.feign.dto.InvoiceRecordVO;
import com.finaudit.starter.web.feign.dto.OcrResultWritebackRequest;
import com.finaudit.starter.web.feign.dto.RuleCheckRequest;
import com.finaudit.starter.web.feign.dto.RuleCheckVO;
import com.finaudit.starter.web.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * agent-core-service 审核数据契约（跨服务 Feign 客户端，统一放 common-code 供 tool-service 复用）。
 * <p>面向工具执行开放的四类只读/回写端点（P2b 审核工具做厚）：
 * OCR 结果回写、部门预算查询、财务规则校验、重复报销检测。
 * 规则评估逻辑归属 agent-core（报销域数据收敛，CLAUDE.md §5.8），tool 只做入参装配与结果聚合。
 * 租户经 {@code X-Tenant-Id} 请求头传递，服务间经 Nacos 服务名直连（不经网关）。</p>
 *
 * <p><b>P3.8 / R0-6：本契约指向内部端点 {@code /internal/audit/**}，不再复用对外端点 {@code /api/v1/audit/**}</b>。
 * 原因：原对外端点仅校验 {@code X-Tenant-Id} 非空即放行，被网关暴露后任意登录用户可调用，
 * 其中 OCR 结果回写为写操作，可篡改审核结论依据。内部前缀不在网关路由表内，外部不可达。</p>
 *
 * <p>⚠️ 与 agent-core 的 {@code InternalAuditDataController} 成对修改</p>
 */
@FeignClient(name = "agent-core-service")
public interface AgentCoreServiceFeign {

    /**
     * OCR 结果回写：按 file_record_id 定位 expense_attachment，回填 ocr_status/file_type/ocr_result。
     *
     * @param tenantId     租户ID（经 X-Tenant-Id 请求头传递）
     * @param fileRecordId file_record id（附件引用）
     * @param request      回写业务字段
     * @return 成功空响应
     */
    @PostMapping("/internal/audit/attachments/{fileRecordId}/ocr-result")
    R<Void> writebackOcrResult(@RequestHeader("X-Tenant-Id") Long tenantId,
                               @PathVariable("fileRecordId") Long fileRecordId,
                               @RequestBody OcrResultWritebackRequest request);

    /**
     * 部门预算查询（按部门+周期，旧契约——存量任务 dept_id 为空的兜底路径）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param deptName 部门（提交时快照名）
     * @param period   预算周期 YYYY-MM
     * @return 部门预算；未配置时 data=null
     */
    @GetMapping("/internal/audit/budgets")
    R<BudgetVO> queryBudget(@RequestHeader("X-Tenant-Id") Long tenantId,
                            @RequestParam("deptName") String deptName,
                            @RequestParam("period") String period);

    /**
     * 部门预算查询（按部门 ID + 周期，P3.5b 权威关联键）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param deptId   部门 ID
     * @param period   预算周期 YYYY-MM
     * @return 部门预算；未配置时 data=null
     */
    @GetMapping("/internal/audit/budgets")
    R<BudgetVO> queryBudgetByDeptId(@RequestHeader("X-Tenant-Id") Long tenantId,
                                    @RequestParam("deptId") Long deptId,
                                    @RequestParam("period") String period);

    /**
     * budget_query 越权校验（P3.5b 收紧，销 P3c「告警不阻断」）：有 reimbId 校验
     * 预算行 dept_id == reimb.dept_id（提交者本人部门语义）；无 reimbId（调试直调）仅查 sys_dept 存在性。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param reimbId  报销单 ID（可空）
     * @param deptId   请求的部门 ID
     * @return true=允许；false=越权/部门不存在/跨租户
     */
    @GetMapping("/internal/audit/budgets/allowed")
    R<Boolean> isBudgetQueryAllowed(@RequestHeader("X-Tenant-Id") Long tenantId,
                                    @RequestParam(value = "reimbId", required = false) Long reimbId,
                                    @RequestParam(value = "deptId", required = false) Long deptId);

    /**
     * 财务规则校验（agent-core 按 finance_rule 评估，返回命中规则 + 是否超标）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param request  校验入参
     * @return 命中规则列表 + 超标标记
     */
    @PostMapping("/internal/audit/rules/check")
    R<RuleCheckVO> checkRules(@RequestHeader("X-Tenant-Id") Long tenantId,
                              @RequestBody RuleCheckRequest request);

    /**
     * 重复报销检测：按申请人 + 金额 + 日期区间 + 商户（OCR 双侧可得时）查历史报销单。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param reimbId  当前报销单ID
     * @return 疑似重复列表（无则为空）
     */
    @GetMapping("/internal/audit/reimbursements/duplicates")
    R<DuplicateCheckVO> queryDuplicates(@RequestHeader("X-Tenant-Id") Long tenantId,
                                        @RequestParam("reimbId") Long reimbId);

    /**
     * 查询报销单归属租户（P3c 工具防越权：duplicate_check/ocr_extract 校验 reimbId 归属）。
     *
     * @param tenantId 当前租户ID（经 X-Tenant-Id 请求头传递）
     * @param reimbId  报销单ID
     * @return 该报销单的 tenantId；不存在返回 data=null（越权/不存在）
     */
    @GetMapping("/internal/audit/reimbursements/{reimbId}/tenant")
    R<Long> findReimbTenantId(@RequestHeader("X-Tenant-Id") Long tenantId,
                              @PathVariable("reimbId") Long reimbId);

    /**
     * 查询报销单的发票标识符投影（P3.8 R3，invoice_match 工具数据源）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param reimbId  报销单ID
     * @return 该单的发票列表（无票号投影则为空列表）
     */
    @GetMapping("/internal/audit/reimbursements/{reimbId}/invoices")
    R<List<InvoiceRecordVO>> listInvoicesByReimb(@RequestHeader("X-Tenant-Id") Long tenantId,
                                                 @PathVariable("reimbId") Long reimbId);

    /**
     * 票据-明细交叉核验 + 离线规则验真（P3.8 R3-3 / R3-5，invoice_match 工具）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param reimbId  报销单ID
     * @param request  申报明细与合计
     * @return 核验结果（票面 vs 明细差额、异常清单）
     */
    @PostMapping("/internal/audit/reimbursements/{reimbId}/invoice-match")
    R<InvoiceMatchVO> matchInvoices(@RequestHeader("X-Tenant-Id") Long tenantId,
                                    @PathVariable("reimbId") Long reimbId,
                                    @RequestBody InvoiceMatchRequest request);
}
