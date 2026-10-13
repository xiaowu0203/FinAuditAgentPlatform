package com.finaudit.toolservice.service;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.enums.ToolCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具防越权守卫（P3c 安全风控；P3.8 R6-2 重做租户基准）。
 * <p>在 {@link ToolRegistryService#execute} 统一入口、入参 Schema 校验之后、执行器分发之前调用，
 * 同时覆盖 HTTP 调试直调与 MQ 两条链路。四道校验：</p>
 * <ul>
 *   <li><b>权威租户一致性</b>：{@link ToolTenantCredential#authTenantId()}（HTTP 取网关/JWT 派生上下文）
 *       须与声明租户一致——两者来源分离，故校验真正可触发。</li>
 *   <li><b>任务归属（MQ 链路）</b>：{@code taskId} 经 agent-core 反查真实归属租户，
 *       须与消息声明的租户一致，防伪造消息跨租户执行。</li>
 *   <li><b>部门归属（budget_query）</b>：入参 deptId 与报销单 dept_id 一致且部门为真实 sys_dept（P3.5b，经 agent-core 校验），防跨部门/虚构部门查询。</li>
 *   <li><b>单据归属（duplicate_check / ocr_extract / invoice_match）</b>：入参 reimbId 须属于当前租户（经 agent-core 校验），防操作他租户单据。</li>
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
     * @param credential  租户凭证（权威租户 / 声明租户 / 任务 ID 分离传入，见 {@link ToolTenantCredential}）
     * @param code        工具编码
     * @param inputParams 执行入参
     */
    public void check(ToolTenantCredential credential, ToolCode code, Map<String, Object> inputParams) {
        checkTenantConsistency(credential);
        switch (code) {
            case BUDGET_QUERY -> checkDeptOwnership(credential.declaredTenantId(), inputParams);
            // INVOICE_MATCH 同样按 reimbId 取发票投影，必须做同一道归属校验（P3.8 R3）
            case DUPLICATE_CHECK, OCR_EXTRACT, INVOICE_MATCH -> checkReimbOwnership(credential.declaredTenantId(), inputParams);
            default -> {
                // 其余工具暂无跨域入参，无需额外校验
            }
        }
    }

    /**
     * 租户一致性校验（P3.8 R6-2 重做）。
     *
     * <p><b>为什么重做</b>：原实现拿 {@code TenantContextHolder} 与声明租户比对，而两条链路里
     * 这个上下文都是<b>用声明租户自己设的</b>（MQ 消费者 {@code runWith(msg.tenantId())}；
     * HTTP 两者同取 {@code X-Tenant-Id}），因此永远相等——校验在生产路径恒等通过。</p>
     *
     * <p><b>现在</b>：权威租户显式传入并与声明租户分离。</p>
     * <ul>
     *   <li>HTTP 链路（authTenantId 非空）：直接比对，不一致即拒绝；该值由网关从 JWT 快照注入，
     *       且 {@code ToolController} 已保证其为空时直接拒绝（fail-closed）。</li>
     *   <li>MQ 链路（authTenantId 为空且有 taskId）：改用<b>任务归属反查</b>——
     *       任务真实所属租户取自 agent-core 库内记录，与消息声明相互独立。</li>
     *   <li>两者皆无（内部/单测直调）：降级为不阻断，仅 trace 级日志留痕。</li>
     * </ul>
     */
    private void checkTenantConsistency(ToolTenantCredential credential) {
        Long authTenant = credential.authTenantId();
        Long declared = credential.declaredTenantId();
        if (authTenant != null) {
            if (!authTenant.equals(declared)) {
                log.warn("权威租户与声明租户不一致，拒绝执行: 权威(网关/JWT)={}, 声明={}", authTenant, declared);
                throw new BizException("租户不一致，拒绝执行: 权威租户=" + authTenant + ", 声明租户=" + declared);
            }
            return;
        }
        Long taskId = credential.taskId();
        if (taskId == null) {
            log.trace("工具执行无租户凭证（内部直调），跳过权威租户校验: declared={}", declared);
            return;
        }
        if (declared == null) {
            throw new BizException("工具执行缺少声明租户，拒绝执行: taskId=" + taskId);
        }
        R<Long> resp = agentCoreServiceFeign.findTaskTenantId(declared, taskId);
        if (resp.getCode() != 0) {
            throw new BizException("任务归属校验失败: " + resp.getMessage());
        }
        Long ownerTenant = resp.getData();
        if (ownerTenant == null || !ownerTenant.equals(declared)) {
            log.warn("拒绝跨租户任务执行: declared={}, taskId={}, ownerTenant={}", declared, taskId, ownerTenant);
            throw new BizException("禁止跨租户任务执行: 任务[" + taskId + "]不存在或不属于当前租户");
        }
    }

    /**
     * budget_query 部门校验（P3.5b 收紧，销 P3c「非已知部门告警不阻断」TODO）。
     *
     * <p><b>定位方式（P3.8 R6-4 对齐三层契约）</b>：{@code deptName} 与 {@code deptId} <b>二者任一</b>即可——
     * 工具实现（{@code BudgetQueryTool}）本就接受任一，入参 Schema 也已改为 {@code anyOf} 二选一，
     * 只剩本守卫要求 deptName，成了「契约说不拦、守卫却拦」的不一致（实测报错
     * {@code budget_query 部门不能为空}，而调用方压根不知道自己违反了哪条契约）。
     * 两层都缺才拒绝，与 Schema 的必填口径一致。</p>
     *
     * <p>此后按凭证分级：
     * ① 有凭证（deptId 或 reimbId，新流水线均携带）→ 严格校验：经 agent-core 判定
     *    「预算行 dept_id == 报销单 dept_id」（本人部门语义）且部门为真实 sys_dept，不通过即<b>拒绝</b>；
     * ② 无凭证（存量任务/调试直调）→ 仅告警留痕，不阻断（向后兼容旧任务入参）。</p>
     */
    private void checkDeptOwnership(Long tenantId, Map<String, Object> inputParams) {
        Object dept = inputParams == null ? null : inputParams.get("deptName");
        Long deptId = asLong(inputParams == null ? null : inputParams.get("deptId"));
        boolean blankDeptName = dept == null || dept.toString().isBlank();
        if (blankDeptName && deptId == null) {
            throw new BizException("budget_query 需提供 deptName 或 deptId");
        }
        Long reimbId = asLong(inputParams == null ? null : inputParams.get("reimbId"));
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
     * duplicate_check / ocr_extract / invoice_match 单据归属：入参 reimbId 须属于当前租户。
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
