package com.finaudit.agentcore.service;

import com.finaudit.agentcore.config.AgentExecutionProperties;
import com.finaudit.agentcore.domain.FlowDecision;
import com.finaudit.agentcore.domain.SelfCheckResult;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.mq.message.ToolResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收尾闸口 + 自纠错单测（P3.8 R5-3）。
 *
 * <p><b>为什么必须补这层测试</b>：R5 上线后实测发现任务 8 步全 SUCCESS 却卡在 RUNNING、
 * {@code result} 与 {@code self_check_result} 均为 NULL —— 说明 {@code finalizeSuccess} 内抛了异常，
 * 而该方法是 private、此前只被守卫用例间接覆盖。本测试通过公开入口
 * {@code onToolResult} 驱动收尾路径，把「收尾是否抛异常」变成可断言的事实。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorSelfCheckTest {

    @Mock
    private AgentTaskService taskService;
    @Mock
    private AgentTaskStepService stepService;
    @Mock
    private TaskPlanner planner;
    @Mock
    private RuleBasedFlowEngine flowEngine;
    @Mock
    private ReviewFlowDecider reviewFlowDecider;
    @Mock
    private TaskEventPublisher eventPublisher;
    @Mock
    private ChatClientFactory modelFactory;
    @Mock
    private AiClient modelClient;
    @Mock
    private ReimbursementService reimbursementService;
    @Mock
    private AuditTicketService auditTicketService;
    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private BudgetOccupancyService budgetOccupancyService;
    @Mock
    private SelfConsistencyChecker selfConsistencyChecker;

    @InjectMocks
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        lenient().when(modelFactory.getClient(ModelType.DEEPSEEK)).thenReturn(modelClient);
        lenient().when(executionProperties.getTaskTimeoutMinutes()).thenReturn(30);
    }

    private static AgentTask runningReimbTask() {
        AgentTask t = new AgentTask();
        t.setId(100L);
        t.setTenantId(1L);
        t.setTaskNo("T202601010000000001");
        t.setTitle("测试报销单");
        t.setTaskType("REIMBURSEMENT");
        t.setStatus(TaskStatus.RUNNING.name());
        t.setTotalSteps(2);
        t.setFinishedSteps(1);
        return t;
    }

    private static AgentTaskStep successStep(Long id, int no, String tool) {
        return step(id, no, tool, "SUCCESS");
    }

    private static AgentTaskStep step(Long id, int no, String tool, String status) {
        AgentTaskStep s = new AgentTaskStep();
        s.setId(id);
        s.setTaskId(100L);
        s.setStepNo(no);
        s.setStepName("步骤" + no);
        s.setStepType(tool == null ? "LLM" : "TOOL");
        s.setToolName(tool);
        s.setStatus(status);
        s.setOutput(Map.of("ok", true));
        return s;
    }

    /** 最后一个工具步骤回到成功 → 收尾；此时所有步骤均已 SUCCESS */
    /**
     * 最后一个工具步骤回到成功 → 收尾；此时所有步骤均已 SUCCESS。
     *
     * <p>用<b>可变列表</b>模拟真实状态流转：{@code resetForSelfCorrection} 桩实现会就地
     * 把风控步骤改为 PENDING，于是后续「重新加载」看到的就是重置后的状态——
     * 这与真实 DB 的行为一致（此前用固定列表，重置是空操作，掩盖了分支走向）。</p>
     *
     * @param pendingAfterReset true = 期望走重跑路径（重置后风控步骤变 PENDING）
     */
    private void driveFinalize(boolean pendingAfterReset) {
        AgentTaskStep step = successStep(9L, 2, "rule_check");
        List<AgentTaskStep> steps = new ArrayList<>(List.of(successStep(8L, 1, "amount_verify"), step));
        when(stepService.findById(9L)).thenReturn(step);
        when(taskService.getRequired(100L)).thenReturn(runningReimbTask());
        when(stepService.markSuccess(any(), any())).thenReturn(true);
        when(stepService.listByTask(100L)).thenReturn(steps);
        // 仅在重跑路径（dispatch）才会用到，AUTO_PASS 路径用不到 → lenient
        lenient().when(stepService.markRunning(any())).thenReturn(true);
        lenient().when(taskService.applySelfCheckResult(any(), any())).thenReturn(true);
        if (pendingAfterReset) {
            when(stepService.resetForSelfCorrection(any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<AgentTaskStep> target = inv.getArgument(0);
                target.forEach(s -> s.setStatus("PENDING"));
                return target.size();
            });
        }
        orchestrator.onToolResult(new ToolResultMessage(100L, 9L, 1L, "RULE_CHECK",
                Map.of("ok", true), true, null, 1L));
    }

    /** 收尾时全部步骤 SUCCESS（不预期重跑） */
    private void driveFinalize() {
        driveFinalize(false);
    }

    @Test
    void finalizeDoesNotThrowWhenSelfCheckPasses() {
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.pass(5));
        when(reviewFlowDecider.decide(any())).thenReturn(FlowDecision.autoPass());
        when(budgetOccupancyService.canOccupy(any(), any())).thenReturn(true);
        when(taskService.markSuccess(any(), any(), anyInt())).thenReturn(true);

        assertDoesNotThrow(() -> { driveFinalize(); }, "自校验通过时收尾不应抛异常");

        // 自校验结果必须落库（这是 R5-4 的可见性来源）
        verify(taskService).applySelfCheckResult(any(), any());
        // AUTO_PASS 路径：标记任务成功
        verify(taskService).markSuccess(any(), any(), anyInt());
    }

    @Test
    void finalizeDoesNotThrowWhenSelfCheckFailsAndRerunsRiskStep() {
        // 命中矛盾：应重置风控步骤并直接分发第一个 PENDING 步骤（不得再走 continueTask，那会无限递归）
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.fail(
                List.of(new SelfCheckResult.Contradiction("AMOUNT_VERIFY_VS_APPROVE",
                        SelfCheckResult.TYPE_CONTRADICTION, "金额核验不一致但结论 APPROVE")), 5));
        when(taskService.incrementCorrectionCount(any())).thenReturn(1);

        assertDoesNotThrow(() -> { driveFinalize(true); },
                "自校验不通过时收尾也不应抛异常（应转入重跑）");

        verify(stepService).resetForSelfCorrection(any());
        verify(taskService).incrementCorrectionCount(any());
        // 重跑路径不得直接标记任务成功
        verify(taskService, never()).markSuccess(any(), any(), anyInt());
    }

    /**
     * 回归护栏：自校验命中矛盾时，重置后必须直接分发步骤。
     * <p>此前实现调用了 {@code continueTask}，它读到未刷新的 SUCCESS 状态又回到收尾，
     * 形成无限递归直至 {@code StackOverflowError}（实测现象：任务 8 步全 SUCCESS 却永久 RUNNING）。</p>
     */
    @Test
    void rerunPathDispatchesPendingStepInsteadOfReenteringContinueTask() {
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.fail(
                List.of(new SelfCheckResult.Contradiction("A", SelfCheckResult.TYPE_CONTRADICTION, "d")), 5));
        when(taskService.incrementCorrectionCount(any())).thenReturn(1);

        driveFinalize(true);

        // 直接分发（dispatch → markRunning），而不是回到 continueTask 重新收尾
        verify(stepService).markRunning(any());
    }

    @Test
    void finalizeReportsReviewWhenCorrectionLimitReached() {
        AgentTask task = runningReimbTask();
        task.setCorrectionCount(1);   // 已达上限
        when(stepService.findById(9L)).thenReturn(successStep(9L, 2, "rule_check"));
        when(taskService.getRequired(100L)).thenReturn(task);
        when(stepService.markSuccess(any(), any())).thenReturn(true);
        when(stepService.listByTask(100L)).thenReturn(List.of(
                successStep(8L, 1, "amount_verify"), successStep(9L, 2, "rule_check")));
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.fail(
                List.of(new SelfCheckResult.Contradiction("DUPLICATE_HIGH_VS_HIGH_CONFIDENCE",
                        SelfCheckResult.TYPE_CONTRADICTION, "硬命中重复但置信度 0.95")), 5));
        when(reviewFlowDecider.decide(any())).thenReturn(FlowDecision.autoPass());

        assertDoesNotThrow(() -> orchestrator.onToolResult(new ToolResultMessage(
                100L, 9L, 1L, "RULE_CHECK", Map.of("ok", true), true, null, 1L)));

        // 超限不再重跑，直接转人工
        verify(stepService, never()).resetForSelfCorrection(any());
        verify(auditTicketService).enterApproval(any(), any(), anyInt(), any(), any());
    }

    @Test
    void selfCheckResultCarriesContradictionDetails() {
        SelfCheckResult r = SelfCheckResult.fail(List.of(
                new SelfCheckResult.Contradiction("A", SelfCheckResult.TYPE_CONTRADICTION, "d1"),
                new SelfCheckResult.Contradiction("B", SelfCheckResult.TYPE_HALLUCINATION, "d2")), 5);

        Map<String, Object> m = r.toResultMap();
        assertEquals(false, m.get("coherent"));
        assertEquals(5, m.get("checkedCount"));
        assertEquals(true, m.get("hallucination"), "含幻觉类型时应为 true（P4 幻觉率指标）");
        assertTrue(m.get("contradictions") instanceof List<?>);
    }
}
