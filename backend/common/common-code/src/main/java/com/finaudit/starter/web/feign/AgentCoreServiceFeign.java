package com.finaudit.starter.web.feign;

import com.finaudit.starter.web.feign.dto.BudgetVO;
import com.finaudit.starter.web.feign.dto.DuplicateCheckVO;
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

/**
 * agent-core-service 审核数据契约（跨服务 Feign 客户端，统一放 common-code 供 tool-service 复用）。
 * <p>面向工具执行开放的四类只读/回写端点（P2b 审核工具做厚）：
 * OCR 结果回写、部门预算查询、财务规则校验、重复报销检测。
 * 规则评估逻辑归属 agent-core（报销域数据收敛，CLAUDE.md §5.8），tool 只做入参装配与结果聚合。
 * 租户经 {@code X-Tenant-Id} 请求头传递，服务间经 Nacos 服务名直连（不经网关）。</p>
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
    @PostMapping("/api/v1/audit/attachments/{fileRecordId}/ocr-result")
    R<Void> writebackOcrResult(@RequestHeader("X-Tenant-Id") Long tenantId,
                               @PathVariable("fileRecordId") Long fileRecordId,
                               @RequestBody OcrResultWritebackRequest request);

    /**
     * 部门预算查询（按部门+周期）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param deptName 部门
     * @param period   预算周期 YYYY-MM
     * @return 部门预算；未配置时 data=null
     */
    @GetMapping("/api/v1/audit/budgets")
    R<BudgetVO> queryBudget(@RequestHeader("X-Tenant-Id") Long tenantId,
                            @RequestParam("deptName") String deptName,
                            @RequestParam("period") String period);

    /**
     * 财务规则校验（agent-core 按 finance_rule 评估，返回命中规则 + 是否超标）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param request  校验入参
     * @return 命中规则列表 + 超标标记
     */
    @PostMapping("/api/v1/audit/rules/check")
    R<RuleCheckVO> checkRules(@RequestHeader("X-Tenant-Id") Long tenantId,
                              @RequestBody RuleCheckRequest request);

    /**
     * 重复报销检测：按申请人 + 金额 + 日期区间 + 商户（OCR 双侧可得时）查历史报销单。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param reimbId  当前报销单ID
     * @return 疑似重复列表（无则为空）
     */
    @GetMapping("/api/v1/audit/reimbursements/duplicates")
    R<DuplicateCheckVO> queryDuplicates(@RequestHeader("X-Tenant-Id") Long tenantId,
                                        @RequestParam("reimbId") Long reimbId);

    /**
     * 校验部门是否属于当前租户（P3c 工具防越权：budget_query 限本部门）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param deptName 待校验部门
     * @return true=该部门为租户已知部门；false=非本租户部门（越权/虚构）
     */
    @GetMapping("/api/v1/audit/budgets/dept-exists")
    R<Boolean> isTenantDept(@RequestHeader("X-Tenant-Id") Long tenantId,
                            @RequestParam("deptName") String deptName);

    /**
     * 查询报销单归属租户（P3c 工具防越权：duplicate_check/ocr_extract 校验 reimbId 归属）。
     *
     * @param tenantId 当前租户ID（经 X-Tenant-Id 请求头传递）
     * @param reimbId  报销单ID
     * @return 该报销单的 tenantId；不存在返回 data=null（越权/不存在）
     */
    @GetMapping("/api/v1/audit/reimbursements/{reimbId}/tenant")
    R<Long> findReimbTenantId(@RequestHeader("X-Tenant-Id") Long tenantId,
                              @PathVariable("reimbId") Long reimbId);
}
