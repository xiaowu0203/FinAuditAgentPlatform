package com.finaudit.agentcore.service;

import com.finaudit.agentcore.config.AgentExecutionProperties;
import com.finaudit.agentcore.domain.FlowDecision;
import com.finaudit.agentcore.domain.ReviewFinding;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收尾路径逐步隔离测试（P3.8 R5 排查用）。
 *
 * <p>背景：运行时任务 8 步全 SUCCESS 却卡 RUNNING、{@code result} 与 {@code self_check_result} 均 NULL，
 * 说明 {@code finalizeSuccess} 抛异常。但真实栈拿不到（服务日志不落盘），
 * 故用本测试把收尾链路的每个外部调用都替换为「必然成功」的桩，
 * 从而把「哪一步抛异常」变成可观测的事实。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorFinalizeIsolationTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private AgentTaskService taskService;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private AgentTaskStepService stepService;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private TaskPlanner planner;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private RuleBasedFlowEngine flowEngine;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private ReviewFlowDecider reviewFlowDecider;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private TaskEventPublisher eventPublisher;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private ChatClientFactory modelFactory;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private AiClient modelClient;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private ReimbursementService reimbursementService;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private AuditTicketService auditTicketService;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private AgentExecutionProperties executionProperties;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private BudgetOccupancyService budgetOccupancyService;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private SelfConsistencyChecker selfConsistencyChecker;


    @Mock(strictness = Mock.Strictness.LENIENT)
    private NotifyFacade notifyFacade;

    @InjectMocks
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(modelFactory.getClient(ModelType.DEEPSEEK)).thenReturn(modelClient);
        when(executionProperties.getTaskTimeoutMinutes()).thenReturn(30);
        when(stepService.markSuccess(any(), any())).thenReturn(true);
        when(taskService.applySelfCheckResult(any(), any())).thenReturn(true);
        when(taskService.markSuccess(any(), any(), anyInt())).thenReturn(true);
        when(stepService.markRunning(any())).thenReturn(true);
        when(reviewFlowDecider.decide(any())).thenReturn(FlowDecision.autoPass());
        when(budgetOccupancyService.canOccupy(any(), any())).thenReturn(true);
    }

    private void drive() {
        drive("REIMBURSEMENT");
    }

    private void drive(String taskType) {
        drive(taskType, LocalDateTime.now().minusSeconds(1), 1L);
    }

    /**
     * @param stepUpdatedAt 步骤当前 updated_at（TOOL 耗时基准：正常应≈置 RUNNING 的时刻）
     * @param costTimeMs    工具服务自报执行耗时
     */
    private void drive(String taskType, LocalDateTime stepUpdatedAt, Long costTimeMs) {
        AgentTask task = new AgentTask();
        task.setId(100L);
        task.setTenantId(1L);
        task.setTaskNo("T202601010000000001");
        task.setTitle("测试");
        task.setTaskType(taskType);
        task.setStatus(TaskStatus.RUNNING.name());
        task.setTotalSteps(2);

        AgentTaskStep s2 = new AgentTaskStep();
        s2.setId(9L);
        s2.setTaskId(100L);
        s2.setStepNo(2);
        s2.setStepType("TOOL");
        s2.setToolName("rule_check");
        s2.setStatus("SUCCESS");
        s2.setOutput(Map.of("overLimit", false, "hits", List.of()));
        s2.setUpdatedAt(stepUpdatedAt);

        AgentTaskStep s1 = new AgentTaskStep();
        s1.setId(8L);
        s1.setTaskId(100L);
        s1.setStepNo(1);
        s1.setStepType("TOOL");
        s1.setToolName("amount_verify");
        s1.setStatus("SUCCESS");
        s1.setOutput(Map.of("match", true));

        List<AgentTaskStep> steps = new ArrayList<>(List.of(s1, s2));
        when(stepService.findById(9L)).thenReturn(s2);
        when(taskService.getRequired(100L)).thenReturn(task);
        when(stepService.listByTask(100L)).thenReturn(steps);

        orchestrator.onToolResult(new ToolResultMessage(100L, 9L, 1L, "RULE_CHECK",
                Map.of("overLimit", false), true, null, costTimeMs));
    }

    @Test
    void finalizeSucceedsWhenSelfCheckCoherentAndEverythingStubbedOk() {
        // 所有依赖都返回成功：此时收尾不应抛任何异常
        // （若本用例失败，说明异常来自编排器自身逻辑或 Jackson 转换，而非外部依赖）
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.pass(5));

        assertDoesNotThrow(() -> { drive(); }, "全桩成功时收尾不应抛异常");
    }

    @Test
    void finalizeSucceedsWhenSelfCheckFailsAndResetWorks() {
        // 重跑路径全桩成功：不应抛异常，且不得卡死
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.fail(
                List.of(new SelfCheckResult.Contradiction("A", SelfCheckResult.TYPE_CONTRADICTION, "d")), 5));
        when(stepService.resetForSelfCorrection(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<AgentTaskStep> target = inv.getArgument(0);
            target.forEach(s -> s.setStatus("PENDING"));
            return target.size();
        });
        when(taskService.incrementCorrectionCount(any())).thenReturn(1);

        assertDoesNotThrow(() -> { drive(); }, "重跑路径全桩成功时不应抛异常");
    }

    @Test
    void jacksonCanConvertSelfCheckResultToMap() {
        // 单独验证 convertValue：这是 passSelfCheckOrCorrect 里唯一涉及序列化的一步
        SelfCheckResult pass = SelfCheckResult.pass(5);
        SelfCheckResult fail = SelfCheckResult.fail(List.of(
                new SelfCheckResult.Contradiction("A", SelfCheckResult.TYPE_CONTRADICTION, "d")), 5);

        assertDoesNotThrow(() -> pass.toResultMap(), "toResultMap 不应抛异常");
        assertDoesNotThrow(() -> fail.toResultMap(), "含矛盾的 toResultMap 不应抛异常");
        assertDoesNotThrow(() -> fail.toFindings(), "toFindings 不应抛异常");
        assertDoesNotThrow(() -> fail.toPromptHint(), "toPromptHint 不应抛异常");
    }

    // ---------------- P3.8 R6-1：GENERIC 通用分析任务的产品化收尾 ----------------

    /**
     * GENERIC 任务同样过自校验闸口。
     *
     * <p>改造前 GENERIC 是「遗留调试通道」：收尾直接 {@code markSuccess}，
     * 既不过自校验也不建工单——LLM 给出 REJECT/存疑结论时任务照样"成功"，风险无人接手。</p>
     */
    @Test
    void genericTaskRunsSelfCheckBeforeFinalize() {
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.pass(5));

        drive("GENERIC");

        verify(selfConsistencyChecker).check(any());
        verify(taskService).applySelfCheckResult(any(), any());
    }

    /** GENERIC 结论非通过（NEED_REVIEW）→ 建审批工单，且不得标记任务成功 */
    @Test
    void genericTaskEntersApprovalWhenDecisionNotApprove() {
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.pass(5));
        when(reviewFlowDecider.decide(any())).thenReturn(FlowDecision.needReview(List.of(
                new ReviewFinding("LLM_DECISION", ReviewFinding.LEVEL_RISK_HIT, null, null, null, null, null,
                        "分析结论为 REJECT，需人工确认"))));

        drive("GENERIC");

        verify(auditTicketService).enterApproval(any(), any(), anyInt(), any(), any());
        verify(taskService, never()).markSuccess(any(), any(), anyInt());
    }

    /** R9-1 接出口：GENERIC 结论通过（AUTO_PASS）→ 收尾成功，不建工单 */
    @Test
    void genericTaskAutoPassesWhenDecisionApprove() {
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.pass(5));
        when(reviewFlowDecider.decide(any())).thenReturn(FlowDecision.autoPass());

        drive("GENERIC");

        verify(taskService).markSuccess(any(), any(), anyInt());
        verify(auditTicketService, never()).enterApproval(any(), any(), anyInt(), any(), any());
    }

    // ---------------- R9-2：TOOL 步骤耗时口径与陈旧基准护栏 ----------------

    /** 正常基准：updated_at ≈ 置 RUNNING 时刻 → 耗时取墙钟（约 1s），不是工具自报值 */
    @Test
    void toolStepDurationUsesWallClockWhenBaselineIsFresh() {
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.pass(5));

        drive("REIMBURSEMENT", LocalDateTime.now().minusSeconds(2), 500L);

        ArgumentCaptor<Long> dur = ArgumentCaptor.forClass(Long.class);
        verify(stepService).updateDuration(eq(9L), dur.capture());
        // 墙钟 ≥ 2s（含 MQ 往返），远大于工具自报 500ms → 说明用的是墙钟口径
        assertTrue(dur.getValue() >= 1500L, "应使用墙钟耗时，实际=" + dur.getValue());
    }

    /**
     * 护栏：`updated_at` 未按预期刷新（陈旧基准）时，墙钟会虚高到整条流水线时长，
     * 此时必须退化为工具自报耗时并告警——否则指标列会被静默污染（R2-10 同类故障的防御）。
     */
    @Test
    void toolStepDurationFallsBackWhenBaselineLooksStale() {
        when(selfConsistencyChecker.check(any())).thenReturn(SelfCheckResult.pass(5));

        // 基准是 10 分钟前（陈旧），工具自报 300ms：正常绝不可能是 10 分钟
        drive("REIMBURSEMENT", LocalDateTime.now().minusMinutes(10), 300L);

        ArgumentCaptor<Long> dur = ArgumentCaptor.forClass(Long.class);
        verify(stepService).updateDuration(eq(9L), dur.capture());
        assertEquals(300L, dur.getValue(), "陈旧基准时应退化为工具自报耗时，实际=" + dur.getValue());
    }
}
