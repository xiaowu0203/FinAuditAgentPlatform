package com.finaudit.agentcore.service;

import com.finaudit.agentcore.mapper.BudgetMapper;
import com.finaudit.agentcore.mapper.BudgetOccupancyMapper;
import com.finaudit.agentcore.pojo.entity.Budget;
import com.finaudit.agentcore.pojo.entity.BudgetOccupancy;
import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import com.finaudit.starter.web.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算占用/释放单测（P3.8 R1）。
 *
 * <p>覆盖重点是**幂等与分支**：重复占用不二次累加、重复释放不二次扣减、未配置预算跳过、
 * 额度不足拦截、resubmit 重跑释放后再占用。这些是"预算被重复吃掉或被永久吃掉"的直接防线。</p>
 *
 * <p><b>并发正确性不在此验证</b>：超支拦截由 {@code BudgetMapper.occupy} 的原子 UPDATE 在数据库层保证，
 * 单测恒真证明不了它。并发验证见 {@code docs/test/budget-occupancy-concurrency.md} 的 MySQL 实测脚本。</p>
 */
@ExtendWith(MockitoExtension.class)
class BudgetOccupancyServiceTest {

    @Mock
    private BudgetMapper budgetMapper;
    @Mock
    private BudgetOccupancyMapper occupancyMapper;
    @Mock
    private ReimbursementService reimbursementService;

    @InjectMocks
    private BudgetOccupancyService service;

    private static final Long TENANT = 1L;
    private static final Long REIMB = 100L;
    private static final Long TASK = 200L;
    private static final Long DEPT = 3L;
    private static final String PERIOD = "2026-08";

    private static Budget budget(String total, String used) {
        Budget b = new Budget();
        b.setId(9L);
        b.setTenantId(TENANT);
        b.setDeptId(DEPT);
        b.setPeriod(PERIOD);
        b.setTotalBudget(new BigDecimal(total));
        b.setUsedAmount(new BigDecimal(used));
        return b;
    }

    private static BudgetOccupancy occupiedRecord() {
        BudgetOccupancy o = BudgetOccupancy.occupied(REIMB, TASK, DEPT, PERIOD, new BigDecimal("600.00"));
        o.setId(1L);
        o.setTenantId(TENANT);
        return o;
    }

    private static BudgetOccupancy releasedRecord() {
        BudgetOccupancy o = occupiedRecord();
        o.applyRelease();
        return o;
    }

    // ---------------- 占用 ----------------

    @Test
    void occupyFirstTimeInsertsRecordAndIncrementsUsedAmount() {
        when(occupancyMapper.selectOne(any())).thenReturn(null);
        when(budgetMapper.findBudgetRow(TENANT, DEPT, PERIOD)).thenReturn(budget("10000.00", "0.00"));
        when(budgetMapper.occupy(TENANT, DEPT, PERIOD, new BigDecimal("600.00"))).thenReturn(1);

        BudgetOccupancyService.OccupyResult result =
                service.occupy(TENANT, REIMB, TASK, DEPT, PERIOD, new BigDecimal("600.00"));

        assertEquals(BudgetOccupancyService.OccupyResult.OCCUPIED, result);
        verify(budgetMapper).occupy(TENANT, DEPT, PERIOD, new BigDecimal("600.00"));
        verify(occupancyMapper).insert(any(BudgetOccupancy.class));
    }

    @Test
    void occupyTwiceIsIdempotentAndDoesNotIncrementAgain() {
        when(occupancyMapper.selectOne(any())).thenReturn(occupiedRecord());

        BudgetOccupancyService.OccupyResult result =
                service.occupy(TENANT, REIMB, TASK, DEPT, PERIOD, new BigDecimal("600.00"));

        assertEquals(BudgetOccupancyService.OccupyResult.ALREADY_OCCUPIED, result);
        verify(budgetMapper, never()).occupy(any(), any(), anyString(), any());
        verify(occupancyMapper, never()).insert(any(BudgetOccupancy.class));
    }

    @Test
    void occupySkipsWhenBudgetNotConfigured() {
        when(occupancyMapper.selectOne(any())).thenReturn(null);
        when(budgetMapper.findBudgetRow(TENANT, DEPT, PERIOD)).thenReturn(null);

        BudgetOccupancyService.OccupyResult result =
                service.occupy(TENANT, REIMB, TASK, DEPT, PERIOD, new BigDecimal("600.00"));

        assertEquals(BudgetOccupancyService.OccupyResult.NOT_CONFIGURED, result);
        verify(budgetMapper, never()).occupy(any(), any(), anyString(), any());
    }

    @Test
    void occupyThrowsWhenInsufficient() {
        when(occupancyMapper.selectOne(any())).thenReturn(null);
        when(budgetMapper.findBudgetRow(TENANT, DEPT, PERIOD)).thenReturn(budget("1000.00", "900.00"));
        when(budgetMapper.occupy(TENANT, DEPT, PERIOD, new BigDecimal("600.00"))).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.occupy(TENANT, REIMB, TASK, DEPT, PERIOD, new BigDecimal("600.00")));
        assertTrue(ex.getMessage().contains("预算不足"));
        // 未成功占用则不得留下记账行
        verify(occupancyMapper, never()).insert(any(BudgetOccupancy.class));
    }

    @Test
    void occupySkipsWhenReimbHasNoDept() {
        BudgetOccupancyService.OccupyResult result =
                service.occupy(TENANT, REIMB, TASK, null, PERIOD, new BigDecimal("600.00"));

        assertEquals(BudgetOccupancyService.OccupyResult.NOT_CONFIGURED, result);
        verify(budgetMapper, never()).findBudgetRow(any(), any(), anyString());
    }

    /** resubmit 重跑：此前已释放 → 释放态下再次占用应真正累加 used_amount 并复位状态。 */
    @Test
    void reoccupyAfterReleaseIncrementsUsedAmountAgain() {
        BudgetOccupancy released = releasedRecord();
        when(occupancyMapper.selectOne(any())).thenReturn(released);
        when(budgetMapper.findBudgetRow(TENANT, DEPT, PERIOD)).thenReturn(budget("10000.00", "0.00"));
        when(budgetMapper.occupy(TENANT, DEPT, PERIOD, new BigDecimal("600.00"))).thenReturn(1);

        BudgetOccupancyService.OccupyResult result =
                service.occupy(TENANT, REIMB, TASK, DEPT, PERIOD, new BigDecimal("600.00"));

        assertEquals(BudgetOccupancyService.OccupyResult.OCCUPIED, result);
        assertTrue(released.isOccupied());
        assertEquals(2, released.getOccupyCount());
        verify(occupancyMapper).updateById(released);
    }

    // ---------------- 释放 ----------------

    @Test
    void releaseDecrementsUsedAmountOnce() {
        BudgetOccupancy occupied = occupiedRecord();
        when(occupancyMapper.selectOne(any())).thenReturn(occupied);

        assertTrue(service.release(REIMB));

        verify(budgetMapper).release(TENANT, DEPT, PERIOD, new BigDecimal("600.00"));
        assertFalse(occupied.isOccupied());
        assertEquals(1, occupied.getReleaseCount());
    }

    @Test
    void releaseTwiceIsIdempotentAndDoesNotDecrementAgain() {
        when(occupancyMapper.selectOne(any())).thenReturn(releasedRecord());

        assertFalse(service.release(REIMB));

        verify(budgetMapper, never()).release(any(), any(), anyString(), any());
    }

    @Test
    void releaseWithoutRecordIsNoop() {
        when(occupancyMapper.selectOne(any())).thenReturn(null);

        assertFalse(service.release(REIMB));

        verify(budgetMapper, never()).release(any(), any(), anyString(), any());
    }

    // ---------------- 只读试算（AUTO_PASS 前的预检） ----------------

    @Test
    void canOccupyTrueWhenRemainingCoversAmount() {
        ExpenseReimbursement reimb = reimb(new BigDecimal("600.00"));
        when(reimbursementService.getByReimbId(REIMB)).thenReturn(reimb);
        when(occupancyMapper.selectOne(any())).thenReturn(null);
        when(budgetMapper.findBudgetRow(TENANT, DEPT, PERIOD)).thenReturn(budget("1000.00", "300.00"));

        assertTrue(service.canOccupy(taskOf(REIMB), null));
    }

    @Test
    void canOccupyFalseWhenRemainingShort() {
        when(reimbursementService.getByReimbId(REIMB)).thenReturn(reimb(new BigDecimal("600.00")));
        when(occupancyMapper.selectOne(any())).thenReturn(null);
        when(budgetMapper.findBudgetRow(TENANT, DEPT, PERIOD)).thenReturn(budget("1000.00", "900.00"));

        assertFalse(service.canOccupy(taskOf(REIMB), null));
    }

    @Test
    void canOccupyTrueWhenBudgetNotConfigured() {
        when(reimbursementService.getByReimbId(REIMB)).thenReturn(reimb(new BigDecimal("600.00")));
        when(occupancyMapper.selectOne(any())).thenReturn(null);
        when(budgetMapper.findBudgetRow(TENANT, DEPT, PERIOD)).thenReturn(null);

        assertTrue(service.canOccupy(taskOf(REIMB), null));
    }

    /** 任务入参无 reimbId（GENERIC 任务）→ 不参与预算占用。 */
    @Test
    void canOccupyTrueWhenTaskHasNoReimbId() {
        assertTrue(service.canOccupy(taskOf(null), null));
        verify(reimbursementService, never()).getByReimbId(any());
    }

    // ---------------- 配平对账 ----------------

    @Test
    void reconciledNetCountsOnlyOccupiedRows() {
        BudgetOccupancy a = occupiedRecord();
        BudgetOccupancy b = releasedRecord();
        b.setId(2L);
        b.setReimbId(101L);
        when(occupancyMapper.selectList(any())).thenReturn(List.of(a, b));

        // RELEASED 行已完成回退（used_amount 已扣回），必须整条跳过：
        // 若再按「占用 − 释放」计算会扣减两次，实测把 600 算成 0（真实 used_amount 是 600）
        assertEquals(0, service.reconciledNet(DEPT, PERIOD).compareTo(a.getAmount()),
                "配平口径应只统计 OCCUPIED 行，释放行不参与");
    }

    @Test
    void reconciledNetZeroWhenAllReleased() {
        BudgetOccupancy a = releasedRecord();
        when(occupancyMapper.selectList(any())).thenReturn(List.of(a));

        // 全部释放 → 无占用净额 → 应为 0（而非负的释放额）
        assertEquals(0, service.reconciledNet(DEPT, PERIOD).compareTo(BigDecimal.ZERO));
    }

    // ---------------- 周期推导 ----------------

    @Test
    void periodDerivedFromClaimDate() {
        assertEquals("2026-08", BudgetOccupancyService.periodOf(LocalDate.of(2026, 8, 31)));
        assertEquals("2026-01", BudgetOccupancyService.periodOf(LocalDate.of(2026, 1, 1)));
    }

    // ---------------- 辅助 ----------------

    private static ExpenseReimbursement reimb(BigDecimal total) {
        ExpenseReimbursement r = new ExpenseReimbursement();
        r.setId(REIMB);
        r.setTenantId(TENANT);
        r.setDeptId(DEPT);
        r.setTotalAmount(total);
        r.setClaimDate(LocalDate.of(2026, 8, 15));
        return r;
    }

    /** 构造带（或不带）reimbId 的任务入参。 */
    private static com.finaudit.agentcore.pojo.entity.AgentTask taskOf(Long reimbId) {
        com.finaudit.agentcore.pojo.entity.AgentTask t = new com.finaudit.agentcore.pojo.entity.AgentTask();
        t.setId(TASK);
        t.setInputParams(reimbId == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(java.util.Map.of("reimbId", reimbId)));
        return t;
    }
}
