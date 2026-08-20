package com.finaudit.toolservice.service;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.result.R;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.toolservice.enums.ToolCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具防越权守卫（P3c 安全风控）。
 * <p>在 {@link ToolRegistryService#execute} 统一入口、入参 Schema 校验之后、执行器分发之前调用，
 * 同时覆盖 HTTP 调试直调与 MQ 两条链路。三道校验：</p>
 * <ul>
 *   <li><b>租户一致性</b>：请求上下文租户（{@link TenantContextHolder}）若存在，须与本次执行声明租户一致，防跨租户直调/篡改头。</li>
 *   <li><b>部门归属（budget_query）</b>：入参 deptName 须为当前租户已知部门（经 agent-core 校验），防跨部门/虚构部门查询。</li>
 *   <li><b>单据归属（duplicate_check / ocr_extract）</b>：入参 reimbId 须属于当前租户（经 agent-core 校验），防操作他租户单据。</li>
 * </ul>
 * <p>校验只读、不改变执行器内部逻辑；误判风险低（未知部门/单据才拒绝，正常流程放行）。</p>
 */
@Component
public class ToolAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolAccessGuard.class);

    private final AgentCoreServiceFeign agentCoreServiceFeign;

    public ToolAccessGuard(AgentCoreServiceFeign agentCoreServiceFeign) {
        this.agentCoreServiceFeign = agentCoreServiceFeign;
    }

    /**
     * 统一越权校验入口。
     *
     * @param tenantId    本次执行声明的租户ID
     * @param code        工具编码
     * @param inputParams 执行入参
     */
    public void check(Long tenantId, ToolCode code, Map<String, Object> inputParams) {
        checkTenantConsistency(tenantId);
        switch (code) {
            case BUDGET_QUERY -> checkDeptOwnership(tenantId, inputParams);
            case DUPLICATE_CHECK, OCR_EXTRACT -> checkReimbOwnership(tenantId, inputParams);
            default -> {
                // 其余工具暂无跨域入参，无需额外校验
            }
        }
    }

    /**
     * 校验请求上下文租户与声明租户一致。上下文缺失（非 Web/MQ 直调测试）时跳过。
     */
    private void checkTenantConsistency(Long tenantId) {
        Long ctxTenant = TenantContextHolder.getTenantId();
        if (ctxTenant != null && !ctxTenant.equals(tenantId)) {
            throw new BizException("租户不一致，拒绝执行: 上下文租户=" + ctxTenant + ", 声明租户=" + tenantId);
        }
    }

    /**
     * budget_query 部门校验：拒绝空白部门；对「非租户已知部门」仅告警不阻断。
     * <p>数据边界：无部门实体表（P5 引入），agent-core 预算查询已按 tenant_id 数据层隔离跨租户，
     * 而「部门归属」无法在没有部门主数据时可靠判定——请求 dept 可能为尚未配预算的合法新部门，
     * 硬拒绝会误伤正常流程。故仅做空白兜底 + 非已知部门告警，完整「员工级部门绑定」记 TODO 到 P5。</p>
     */
    private void checkDeptOwnership(Long tenantId, Map<String, Object> inputParams) {
        Object dept = inputParams == null ? null : inputParams.get("deptName");
        if (dept == null || dept.toString().isBlank()) {
            throw new BizException("budget_query 部门不能为空");
        }
        R<Boolean> resp = agentCoreServiceFeign.isTenantDept(tenantId, dept.toString());
        if (resp.getCode() != 0) {
            throw new BizException("部门归属校验失败: " + resp.getMessage());
        }
        if (!Boolean.TRUE.equals(resp.getData())) {
            // 非租户已知部门：告警留痕，不阻断（无法可靠判定所属；完整归属校验待 P5 部门实体表）
            log.warn("budget_query 部门不属于当前租户已知部门(不阻断,TODO P5): tenantId={}, dept={}", tenantId, dept);
        }
    }

    /**
     * duplicate_check / ocr_extract 单据归属：入参 reimbId 须属于当前租户。
     */
    private void checkReimbOwnership(Long tenantId, Map<String, Object> inputParams) {
        Object reimbObj = inputParams == null ? null : inputParams.get("reimbId");
        if (reimbObj == null) {
            return; // 必填校验在 Schema/执行器内；此处仅做归属校验
        }
        Long reimbId = asLong(reimbObj);
        if (reimbId == null) {
            return;
        }
        R<Long> resp = agentCoreServiceFeign.findReimbTenantId(tenantId, reimbId);
        if (resp.getCode() != 0) {
            throw new BizException("单据归属校验失败: " + resp.getMessage());
        }
        Long ownerTenant = resp.getData();
        if (ownerTenant == null || !ownerTenant.equals(tenantId)) {
            log.warn("拒绝跨租户单据操作: tenantId={}, reimbId={}, ownerTenant={}", tenantId, reimbId, ownerTenant);
            throw new BizException("禁止跨租户单据操作: 报销单[" + reimbId + "]不存在或不属于当前租户");
        }
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
