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
 *   <li><b>部门归属（budget_query）</b>：入参 deptId 与报销单 dept_id 一致且部门为真实 sys_dept（P3.5b，经 agent-core 校验），防跨部门/虚构部门查询。</li>
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
     * budget_query 部门校验（P3.5b 收紧，销 P3c「非已知部门告警不阻断」TODO）。
     * <p>部门名仍须非空；此后按凭证分级：
     * ① 有凭证（deptId 或 reimbId，新流水线均携带）→ 严格校验：经 agent-core 判定
     *    「预算行 dept_id == 报销单 dept_id」（本人部门语义）且部门为真实 sys_dept，不通过即<b>拒绝</b>；
     * ② 无凭证（存量任务/调试直调）→ 仅空名拒绝 + 告警留痕，不阻断（向后兼容旧任务入参）。</p>
     */
    private void checkDeptOwnership(Long tenantId, Map<String, Object> inputParams) {
        Object dept = inputParams == null ? null : inputParams.get("deptName");
        if (dept == null || dept.toString().isBlank()) {
            throw new BizException("budget_query 部门不能为空");
        }
        Long deptId = asLong(inputParams.get("deptId"));
        Long reimbId = asLong(inputParams.get("reimbId"));
        // 无凭证（存量任务/HTTP 直调无 reimbId、无 deptId）：降级告警不阻断
        if (deptId == null && reimbId == null) {
            log.warn("budget_query 无 deptId/reimbId 凭证，降级为部门名校验(不阻断): tenantId={}, dept={}", tenantId, dept);
            return;
        }
        R<Boolean> resp = agentCoreServiceFeign.isBudgetQueryAllowed(tenantId, reimbId, deptId);
        if (resp.getCode() != 0) {
            throw new BizException("预算查询越权校验失败: " + resp.getMessage());
        }
        if (!Boolean.TRUE.equals(resp.getData())) {
            log.warn("budget_query 越权拒绝: tenantId={}, reimbId={}, deptId={}", tenantId, reimbId, deptId);
            throw new BizException("预算查询越权：部门与报销单归属不一致或部门不存在");
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
