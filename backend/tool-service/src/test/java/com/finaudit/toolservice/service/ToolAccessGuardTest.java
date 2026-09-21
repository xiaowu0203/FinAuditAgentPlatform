package com.finaudit.toolservice.service;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.enums.ToolCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 工具防越权守卫单测（P3c；P3.8 R6-2 重做租户基准后重写）。
 *
 * <p><b>为什么要重写</b>：旧用例用 {@code TenantContextHolder.setTenantId(2)} + 声明租户 1 来"制造"
 * 不一致——但生产链路上这个上下文本来就是用声明租户自己设置的（MQ 消费者
 * {@code runWith(msg.tenantId())}），所以那种不一致<b>永远不会发生</b>，用例给出的是虚假的安全感。</p>
 *
 * <p>现在权威租户与声明租户<b>显式分离</b>传入，用例直接构造两种真实故障：</p>
 * <ul>
 *   <li>HTTP：网关/JWT 派生租户 ≠ 请求声明租户（伪造租户头）；</li>
 *   <li>MQ：消息声明的租户 ≠ 任务真实归属租户（伪造消息）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ToolAccessGuardTest {

    @Mock
    private AgentCoreServiceFeign agentCoreServiceFeign;

    @InjectMocks
    private ToolAccessGuard guard;

    /** HTTP 用户侧凭证：权威租户 == 声明租户（正常经网关调用） */
    private static ToolTenantCredential http(Long tenantId) {
        return ToolTenantCredential.http(tenantId, tenantId);
    }

    // ---------------- 权威租户一致性（HTTP） ----------------

    @Test
    void httpAuthTenantMismatchRejects() {
        // 权威租户（网关/JWT）=2，请求声明 =1 → 拒绝（伪造租户头）
        assertThrows(BizException.class,
                () -> guard.check(ToolTenantCredential.http(2L, 1L), ToolCode.AMOUNT_VERIFY, Map.of()));
    }

    @Test
    void httpAuthTenantMatchAllows() {
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.AMOUNT_VERIFY,
                Map.of("claimedTotal", 100, "items", List.of())));
    }

    // ---------------- 任务归属（MQ 链路） ----------------

    @Test
    void mqTaskOwnershipMismatchRejects() {
        // 消息声明租户 1，但任务真实归属租户 2 → 拒绝（伪造消息）
        when(agentCoreServiceFeign.findTaskTenantId(1L, 100L)).thenReturn(R.success(2L));
        assertThrows(BizException.class,
                () -> guard.check(ToolTenantCredential.mq(1L, 100L), ToolCode.AMOUNT_VERIFY, Map.of()));
    }

    @Test
    void mqTaskOwnershipMissingRejects() {
        // 任务查不到（伪造的 taskId 或不属于该租户）→ data=null → 拒绝
        when(agentCoreServiceFeign.findTaskTenantId(1L, 999L)).thenReturn(R.success(null));
        assertThrows(BizException.class,
                () -> guard.check(ToolTenantCredential.mq(1L, 999L), ToolCode.AMOUNT_VERIFY, Map.of()));
    }

    @Test
    void mqTaskOwnershipMatchAllows() {
        when(agentCoreServiceFeign.findTaskTenantId(1L, 100L)).thenReturn(R.success(1L));
        assertDoesNotThrow(() -> guard.check(ToolTenantCredential.mq(1L, 100L), ToolCode.AMOUNT_VERIFY,
                Map.of("claimedTotal", 100, "items", List.of())));
    }

    @Test
    void mqMissingDeclaredTenantRejects() {
        // 有 taskId 却没有声明租户：无法判断归属 → 拒绝（fail-closed）
        assertThrows(BizException.class,
                () -> guard.check(new ToolTenantCredential(null, null, 100L), ToolCode.AMOUNT_VERIFY, Map.of()));
    }

    @Test
    void noCredentialSkipsTenantCheck() {
        // 既无权威租户也无 taskId（内部/单测直调）：跳过权威租户校验，不阻断
        assertDoesNotThrow(() -> guard.check(new ToolTenantCredential(null, 1L, null), ToolCode.AMOUNT_VERIFY,
                Map.of("claimedTotal", 100, "items", List.of())));
    }

    // ---------------- 部门归属（budget_query） ----------------

    @Test
    void budgetQueryBlankDeptRejected() {
        // 部门名与 deptId 都给不出：直接拒绝（不触发 Feign）
        assertThrows(BizException.class, () -> guard.check(http(1L), ToolCode.BUDGET_QUERY,
                Map.of("deptName", "  ", "claimDate", "2026-08-01", "amount", 100)));
    }

    @Test
    void budgetQueryAcceptsDeptIdOnly() {
        // R6-4 三层契约对齐：工具实现与入参 Schema 都接受「deptName 或 deptId」，
        // 守卫不得比契约更严（此前只传 deptId 会被"部门不能为空"拦下，而调用方无从得知）
        when(agentCoreServiceFeign.isBudgetQueryAllowed(1L, null, 1L)).thenReturn(R.success(true));
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.BUDGET_QUERY,
                Map.of("deptId", 1L, "claimDate", "2026-08-01", "amount", 100)));
    }

    @Test
    void budgetQueryRejectsWhenNeitherDeptNameNorDeptId() {
        assertThrows(BizException.class, () -> guard.check(http(1L), ToolCode.BUDGET_QUERY,
                Map.of("claimDate", "2026-08-01", "amount", 100)));
    }

    @Test
    void budgetQueryNoCredentialDegradesWarnNotBlocked() {
        // 无 deptId 且无 reimbId（存量任务/调试直调）：降级为仅空名校验 + 告警，不触发 Feign、不阻断
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.BUDGET_QUERY,
                Map.of("deptName", "市场部", "claimDate", "2026-08-01", "amount", 100)));
    }

    @Test
    void budgetQueryWithCredentialDeniesMismatch() {
        // P3.5b 收紧：有凭证时 agent-core 判定越权（预算行 dept_id 与 reimb.dept_id 不一致/部门不存在）→ 阻断
        when(agentCoreServiceFeign.isBudgetQueryAllowed(1L, 100L, 9L)).thenReturn(R.success(false));
        assertThrows(BizException.class, () -> guard.check(http(1L), ToolCode.BUDGET_QUERY,
                Map.of("deptName", "研发部", "deptId", 9L, "reimbId", 100L,
                        "claimDate", "2026-08-01", "amount", 100)));
    }

    @Test
    void budgetQueryWithCredentialAllowsMatch() {
        // 有凭证且 agent-core 判定本人部门一致 → 放行
        when(agentCoreServiceFeign.isBudgetQueryAllowed(1L, 100L, 9L)).thenReturn(R.success(true));
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.BUDGET_QUERY,
                Map.of("deptName", "研发部", "deptId", 9L, "reimbId", 100L,
                        "claimDate", "2026-08-01", "amount", 100)));
    }

    @Test
    void budgetQueryDeptIdOnlyChecksExistence() {
        // 无 reimbId（HTTP 调试直调带 deptId）：agent-core 仅校验 sys_dept 存在性
        when(agentCoreServiceFeign.isBudgetQueryAllowed(1L, null, 1L)).thenReturn(R.success(true));
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.BUDGET_QUERY,
                Map.of("deptName", "财务部", "deptId", 1L, "claimDate", "2026-08-01", "amount", 100)));
    }

    // ---------------- 单据归属（duplicate_check / ocr_extract / invoice_match） ----------------

    @Test
    void duplicateCheckRejectsCrossTenantReimb() {
        // 该 reimbId 归属租户 2，当前声明租户 1 → 拒绝
        when(agentCoreServiceFeign.findReimbTenantId(1L, 100L)).thenReturn(R.success(2L));
        assertThrows(BizException.class,
                () -> guard.check(http(1L), ToolCode.DUPLICATE_CHECK, Map.of("reimbId", 100L)));
    }

    @Test
    void duplicateCheckRejectsMissingReimb() {
        // reimbId 不存在 → data=null → 拒绝
        when(agentCoreServiceFeign.findReimbTenantId(1L, 999L)).thenReturn(R.success(null));
        assertThrows(BizException.class,
                () -> guard.check(http(1L), ToolCode.DUPLICATE_CHECK, Map.of("reimbId", 999L)));
    }

    @Test
    void duplicateCheckAllowsOwnReimb() {
        when(agentCoreServiceFeign.findReimbTenantId(1L, 200L)).thenReturn(R.success(1L));
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.DUPLICATE_CHECK, Map.of("reimbId", 200L)));
    }

    @Test
    void invoiceMatchValidatesReimbOwnership() {
        // P3.8 R3：invoice_match 同样按 reimbId 取发票数据，必须做同一道归属校验
        when(agentCoreServiceFeign.findReimbTenantId(1L, 400L)).thenReturn(R.success(2L));
        assertThrows(BizException.class, () -> guard.check(http(1L), ToolCode.INVOICE_MATCH,
                Map.of("reimbId", 400L, "items", List.of())));
    }

    @Test
    void invoiceMatchAllowsOwnReimb() {
        when(agentCoreServiceFeign.findReimbTenantId(1L, 500L)).thenReturn(R.success(1L));
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.INVOICE_MATCH,
                Map.of("reimbId", 500L, "items", List.of())));
    }

    @Test
    void ocrExtractValidatesReimbOwnership() {
        when(agentCoreServiceFeign.findReimbTenantId(1L, 300L)).thenReturn(R.success(1L));
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.OCR_EXTRACT,
                Map.of("reimbId", 300L, "attachmentIds", List.of(1L))));
    }

    @Test
    void amountVerifyNoExtraOwnershipCheck() {
        // 无跨域入参：不触发额外校验，直接放行
        assertDoesNotThrow(() -> guard.check(http(1L), ToolCode.AMOUNT_VERIFY,
                Map.of("claimedTotal", 100, "items", List.of())));
    }
}
