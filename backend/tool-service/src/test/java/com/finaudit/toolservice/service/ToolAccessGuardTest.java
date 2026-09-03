package com.finaudit.toolservice.service;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.result.R;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.toolservice.enums.ToolCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 工具防越权守卫单测（P3c）。
 */
@ExtendWith(MockitoExtension.class)
class ToolAccessGuardTest {

    @Mock
    private AgentCoreServiceFeign agentCoreServiceFeign;

    @InjectMocks
    private ToolAccessGuard guard;

    @Test
    void tenantMismatchRejects() {
        TenantContextHolder.setTenantId(2L);
        try {
            // 上下文租户=2，声明租户=1 → 拒绝
            assertThrows(BizException.class, () -> guard.check(1L, ToolCode.BUDGET_QUERY, Map.of()));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void budgetQueryBlankDeptRejected() {
        TenantContextHolder.setTenantId(1L);
        try {
            // 空白部门：直接拒绝（不触发 Feign）
            assertThrows(BizException.class, () -> guard.check(1L, ToolCode.BUDGET_QUERY,
                    Map.of("deptName", "  ", "claimDate", "2026-08-01", "amount", 100)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void budgetQueryNoCredentialDegradesWarnNotBlocked() {
        // 无 deptId 且无 reimbId（存量任务/调试直调）：降级为仅空名校验 + 告警，不触发 Feign、不阻断
        TenantContextHolder.setTenantId(1L);
        try {
            assertDoesNotThrow(() -> guard.check(1L, ToolCode.BUDGET_QUERY,
                    Map.of("deptName", "市场部", "claimDate", "2026-08-01", "amount", 100)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void budgetQueryWithCredentialDeniesMismatch() {
        // P3.5b 收紧：有凭证时 agent-core 判定越权（预算行 dept_id 与 reimb.dept_id 不一致/部门不存在）→ 阻断
        when(agentCoreServiceFeign.isBudgetQueryAllowed(1L, 100L, 9L)).thenReturn(R.success(false));
        TenantContextHolder.setTenantId(1L);
        try {
            assertThrows(BizException.class, () -> guard.check(1L, ToolCode.BUDGET_QUERY,
                    Map.of("deptName", "研发部", "deptId", 9L, "reimbId", 100L,
                            "claimDate", "2026-08-01", "amount", 100)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void budgetQueryWithCredentialAllowsMatch() {
        // 有凭证且 agent-core 判定本人部门一致 → 放行
        when(agentCoreServiceFeign.isBudgetQueryAllowed(1L, 100L, 9L)).thenReturn(R.success(true));
        TenantContextHolder.setTenantId(1L);
        try {
            assertDoesNotThrow(() -> guard.check(1L, ToolCode.BUDGET_QUERY,
                    Map.of("deptName", "研发部", "deptId", 9L, "reimbId", 100L,
                            "claimDate", "2026-08-01", "amount", 100)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void budgetQueryDeptIdOnlyChecksExistence() {
        // 无 reimbId（HTTP 调试直调带 deptId）：agent-core 仅校验 sys_dept 存在性
        when(agentCoreServiceFeign.isBudgetQueryAllowed(1L, null, 1L)).thenReturn(R.success(true));
        TenantContextHolder.setTenantId(1L);
        try {
            assertDoesNotThrow(() -> guard.check(1L, ToolCode.BUDGET_QUERY,
                    Map.of("deptName", "财务部", "deptId", 1L, "claimDate", "2026-08-01", "amount", 100)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void duplicateCheckRejectsCrossTenantReimb() {
        // 该 reimbId 归属租户 2，当前声明租户 1 → 拒绝
        when(agentCoreServiceFeign.findReimbTenantId(1L, 100L)).thenReturn(R.success(2L));
        TenantContextHolder.setTenantId(1L);
        try {
            assertThrows(BizException.class, () -> guard.check(1L, ToolCode.DUPLICATE_CHECK,
                    Map.of("reimbId", 100L)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void duplicateCheckRejectsMissingReimb() {
        // reimbId 不存在 → data=null → 拒绝
        when(agentCoreServiceFeign.findReimbTenantId(1L, 999L)).thenReturn(R.success(null));
        TenantContextHolder.setTenantId(1L);
        try {
            assertThrows(BizException.class, () -> guard.check(1L, ToolCode.DUPLICATE_CHECK,
                    Map.of("reimbId", 999L)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void duplicateCheckAllowsOwnReimb() {
        when(agentCoreServiceFeign.findReimbTenantId(1L, 200L)).thenReturn(R.success(1L));
        TenantContextHolder.setTenantId(1L);
        try {
            assertDoesNotThrow(() -> guard.check(1L, ToolCode.DUPLICATE_CHECK, Map.of("reimbId", 200L)));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void ocrExtractValidatesReimbOwnership() {
        when(agentCoreServiceFeign.findReimbTenantId(1L, 300L)).thenReturn(R.success(1L));
        TenantContextHolder.setTenantId(1L);
        try {
            assertDoesNotThrow(() -> guard.check(1L, ToolCode.OCR_EXTRACT,
                    Map.of("reimbId", 300L, "attachmentIds", java.util.List.of(1L))));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void amountVerifyNoExtraOwnershipCheck() {
        // 无跨域入参：不触发额外校验，直接放行
        TenantContextHolder.setTenantId(1L);
        try {
            assertDoesNotThrow(() -> guard.check(1L, ToolCode.AMOUNT_VERIFY,
                    Map.of("claimedTotal", 100, "items", java.util.List.of())));
        } finally {
            TenantContextHolder.clear();
        }
    }
}
