package com.finaudit.agentcore.service;

import com.finaudit.agentcore.config.AgentExecutionProperties;
import com.finaudit.agentcore.enums.ReimbursementStatus;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.mq.message.ToolResultMessage;
import com.finaudit.starter.web.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排器守卫单测（P3b）：迟到 tool.result 丢弃（CANCELLED/REJECTED）、
 * 续跑拒 CANCELLED 作废任务、failTask 联动 onRerunFail 复位 AMENDED 工单。
 * <p>通过可观测入口（onToolResult / resume）间接覆盖私有 failTask 链路。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorGuardTest {

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

    @InjectMocks
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void stubModelClient() {
        // 构造器内 modelFactory.getClient(DEEPSEEK) 拿到客户端；guard 用例不触发 LLM 调用
        lenient().when(modelFactory.getClient(ModelType.DEEPSEEK)).thenReturn(modelClient);
        // 任务级超时预算（P3.5d）：guard 用例默认 30 分钟
        lenient().when(executionProperties.getTaskTimeoutMinutes()).thenReturn(30);
    }

    // ---------- onToolResult：迟到 tool.result 丢弃 ----------

    @Test
    void onToolResultDropsLateResultForCancelledTask() {
        AgentTask task = task(100L, TaskStatus.CANCELLED);
        AgentTaskStep step = step(1L, 3);
        when(stepService.findById(1L)).thenReturn(step);
        when(taskService.getRequired(100L)).thenReturn(task);

        orchestrator.onToolResult(new ToolResultMessage(100L, 1L, 1L, "RULE_CHECK",
                Map.of("overLimit", true), true, null, 12L));

        // 任务已作废：不得把步骤标 SUCCESS、不得驱动续跑、不得触发失败复位
        verify(stepService, never()).markSuccess(any(), any());
        verify(taskService, never()).markFailed(any(), anyString());
        verify(auditTicketService, never()).onRerunFail(any(AgentTask.class));
    }

    @Test
    void onToolResultDropsLateResultForRejectedTask() {
        AgentTask task = task(100L, TaskStatus.REJECTED);
        AgentTaskStep step = step(1L, 3);
        when(stepService.findById(1L)).thenReturn(step);
        when(taskService.getRequired(100L)).thenReturn(task);

        orchestrator.onToolResult(new ToolResultMessage(100L, 1L, 1L, "RULE_CHECK",
                Map.of("overLimit", true), true, null, 12L));

        verify(stepService, never()).markSuccess(any(), any());
        verify(auditTicketService, never()).onRerunFail(any(AgentTask.class));
    }

    // ---------- failTask 联动：重试耗尽 → 任务失败 + onRerunFail 复位 AMENDED 工单 ----------

    @Test
    void onToolResultMaxRetryTriggersFailAndOnRerunFail() {
        AgentTask task = task(100L, TaskStatus.RUNNING);
        AgentTaskStep step = step(1L, 3);
        when(stepService.findById(1L)).thenReturn(step);
        when(taskService.getRequired(100L)).thenReturn(task);
        // P3.5d CAS 迁移放行（boolean 默认 false 会误触发"竞争失败"跳过分支）
        when(stepService.markFailed(eq(step), anyString())).thenReturn(true);
        when(taskService.markFailed(eq(task), anyString())).thenReturn(true);

        orchestrator.onToolResult(new ToolResultMessage(100L, 1L, 1L, "RULE_CHECK",
                null, false, "规则服务超时", 300L));

        verify(stepService).markFailed(step, "规则服务超时");
        verify(taskService).markFailed(eq(task), anyString());
        verify(reimbursementService).updateStatusByTaskId(100L, ReimbursementStatus.FAILED);
        // 关键：重跑失败 → AMENDED 工单复位 PENDING（防孤儿工单）
        verify(auditTicketService).onRerunFail(task);
    }

    // ---------- P3.5d：CAS 竞争失败 / 任务级超时 / 迟到回调白名单收紧 ----------

    @Test
    void onToolResultDropsLateResultForFailedTask() {
        // P3.5d 收紧：白名单仅 RUNNING。此前 FAILED/APPROVAL_PENDING 的迟到结果仍会把步骤标 SUCCESS
        AgentTask task = task(100L, TaskStatus.FAILED);
        AgentTaskStep step = step(1L, 3);
        when(stepService.findById(1L)).thenReturn(step);
        when(taskService.getRequired(100L)).thenReturn(task);

        orchestrator.onToolResult(new ToolResultMessage(100L, 1L, 1L, "RULE_CHECK",
                Map.of("ok", true), true, null, 12L));

        verify(stepService, never()).markSuccess(any(), any());
        verify(taskService, never()).setFinishedSteps(any(), anyInt());
    }

    @Test
    void startAbortsWhenMarkRunningLosesRace() {
        // 多实例重复消费 task.submit：CAS 抢 RUNNING 失败的一方必须放弃，禁止双份规划+双份步骤
        AgentTask task = task(100L, TaskStatus.PENDING);
        when(taskService.getRequired(100L)).thenReturn(task);
        when(taskService.markRunning(task)).thenReturn(false);

        orchestrator.start(100L);

        verify(flowEngine, never()).plan(any(AgentTask.class));
        verify(planner, never()).plan(any(AgentTask.class));
        verify(stepService, never()).insertPlan(any(), any(), any());
        verify(eventPublisher, never()).publishToolExecute(any(), any());
    }

    @Test
    void onToolResultSuccessSkipsWhenStepCasFails() {
        // 步骤 CAS 写 SUCCESS 失败（并发迁移/迟到结果）：丢弃结果，不刷新计数不推进
        AgentTask task = task(100L, TaskStatus.RUNNING);
        AgentTaskStep step = step(1L, 0);
        when(stepService.findById(1L)).thenReturn(step);
        when(taskService.getRequired(100L)).thenReturn(task);
        when(stepService.markSuccess(eq(step), any())).thenReturn(false);

        orchestrator.onToolResult(new ToolResultMessage(100L, 1L, 1L, "RULE_CHECK",
                Map.of("ok", true), true, null, 12L));

        verify(taskService, never()).setFinishedSteps(any(), anyInt());
        verify(eventPublisher, never()).publishToolExecute(any(), any());
    }

    @Test
    void continueTaskTimeoutFailsTask() {
        // 任务级超时（P3.5d）：started_at 超过预算仍在派发新步骤 → 强制失败终止
        AgentTask task = task(100L, TaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now().minusMinutes(40));
        AgentTaskStep pending = step(1L, 0);
        pending.setStatus("PENDING");
        when(taskService.getRequired(100L)).thenReturn(task);
        when(stepService.listByTask(100L)).thenReturn(List.of(pending));
        when(taskService.markFailed(eq(task), anyString())).thenReturn(true);

        orchestrator.continueTask(100L);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).markFailed(eq(task), errorCaptor.capture());
        assertTrue(errorCaptor.getValue().contains("超时"));
        verify(reimbursementService).updateStatusByTaskId(100L, ReimbursementStatus.FAILED);
        verify(stepService, never()).markRunning(any(AgentTaskStep.class));
    }

    // ---------- resume：拒 CANCELLED 作废任务 ----------

    @Test
    void resumeRejectsCancelledTask() {
        AgentTask task = task(100L, TaskStatus.CANCELLED);
        when(taskService.getRequired(100L)).thenReturn(task);

        BizException ex = assertThrows(BizException.class, () -> orchestrator.resume(100L));
        assertTrue(ex.getMessage().contains("已终结"));
        verify(taskService, never()).markRunning(any(AgentTask.class));
    }

    // ---------- 构造辅助 ----------

    private static AgentTask task(Long id, TaskStatus status) {
        AgentTask t = new AgentTask();
        t.setId(id);
        t.setTenantId(1L);
        t.setTaskNo("T202401010000000001");
        t.setTaskType("REIMBURSEMENT");
        t.setCreatedBy(10L);
        t.setStatus(status.name());
        return t;
    }

    private static AgentTaskStep step(Long id, int retryCount) {
        AgentTaskStep s = new AgentTaskStep();
        s.setId(id);
        s.setTaskId(100L);
        s.setStepNo(1);
        s.setStepName("规则比对");
        s.setStepType("TOOL");
        s.setToolName("RULE_CHECK");
        s.setRetryCount(retryCount);
        return s;
    }
}
