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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
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
        AgentTask task = new AgentTask();
        task.setId(100L);
        task.setTenantId(1L);
        task.setTaskNo("T202601010000000001");
        task.setTitle("测试");
        task.setTaskType("REIMBURSEMENT");
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
                Map.of("overLimit", false), true, null, 1L));
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
}
