package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.AuditAction;
import com.finaudit.agentcore.enums.AuditTicketStatus;
import com.finaudit.agentcore.enums.ReimbursementStatus;
import com.finaudit.agentcore.mapper.AuditRecordMapper;
import com.finaudit.agentcore.mapper.AuditTicketMapper;
import com.finaudit.agentcore.pojo.dto.AuditActionRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementItemRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitResult;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AuditRecord;
import com.finaudit.agentcore.pojo.entity.AuditTicket;
import com.finaudit.agentcore.pojo.vo.AuditTicketVO;
import com.finaudit.starter.redis.lock.DistributedLockTemplate;
import com.finaudit.starter.web.auth.UserContext;
import com.finaudit.starter.web.auth.UserContextHolder;
import com.finaudit.starter.web.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 审批工单服务单测（P3b）：编排器进入/闭合审批态、财务审批动作（approve/reject/terminate/
 * agreeWithdraw/refuseWithdraw）状态机与留痕、提交人动作（resubmit 修改重跑 / withdraw 撤回 /
 * requestWithdraw 发起撤销）、重跑失败复位与权限/重跑上限。
 * <p>DistributedLockTemplate mock 透传 Supplier（锁语义由 common-redis-starter 自身保证）；
 * 提交人动作与财务动作共用同一把锁，锁内重读防丢失更新。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditTicketServiceTest {

    @Mock
    private AuditTicketMapper ticketMapper;
    @Mock
    private AuditRecordMapper recordMapper;
    @Mock
    private AgentTaskService taskService;
    @Mock
    private AgentTaskStepService stepService;
    @Mock
    private ReimbursementService reimbursementService;
    @Mock
    private DistributedLockTemplate lockTemplate;
    @Mock
    private RuleBasedFlowEngine flowEngine;

    @InjectMocks
    private AuditTicketService service;

    @BeforeEach
    void passThroughLock() {
        // lenient：仅锁内动作测试命中，enterApproval/closeOnAutoPass 不触发
        lenient().when(lockTemplate.executeInTx(anyString(), any(Supplier.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<Object> action = (Supplier<Object>) inv.getArgument(1);
            return action.get();
        });
        // 默认审批人上下文（P3.5 起 action 权限判定走 UserContext 的 audit:approve，替换角色字符串）
        UserContextHolder.set(approverContext());
        // P3.5d CAS 状态迁移默认放行（Mockito 对 boolean 默认返回 false，会误触发"竞争失败"分支）
        lenient().when(taskService.markApprovalPending(any(), any(), anyInt())).thenReturn(true);
        lenient().when(taskService.markSuccess(any(), any(), anyInt())).thenReturn(true);
        lenient().when(taskService.markRejected(any(AgentTask.class))).thenReturn(true);
        lenient().when(taskService.markTerminated(any(AgentTask.class))).thenReturn(true);
        lenient().when(taskService.markCancelled(any(AgentTask.class), anyString())).thenReturn(true);
        lenient().when(taskService.prepareRerun(any(AgentTask.class), any())).thenReturn(true);
    }

    @AfterEach
    void clearUserContext() {
        UserContextHolder.clear();
    }

    /** 审批人上下文：audit:approve 权限命中，action 内权限兜底放行。 */
    private UserContext approverContext() {
        UserContext context = new UserContext();
        context.setUserId(20L);
        context.setPerms(new LinkedHashSet<>(Set.of("audit:approve", "audit:viewAll")));
        return context;
    }

    // ---------- enterApproval ----------

    @Test
    void enterApprovalCreatesTicketAndSubmitRecord() {
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectOne(any())).thenReturn(null);

        service.enterApproval(task, Map.<String, Object>of("summary", "审核流程执行完成"), 6,
                List.of("OVER_LIMIT:大额限额 超标"));

        verify(taskService).markApprovalPending(eq(task), any(), eq(6));
        verify(reimbursementService).updateStatusByTaskId(100L, ReimbursementStatus.MANUAL_REVIEW);

        ArgumentCaptor<AuditTicket> ticketCaptor = ArgumentCaptor.forClass(AuditTicket.class);
        verify(ticketMapper).insert(ticketCaptor.capture());
        AuditTicket created = ticketCaptor.getValue();
        assertEquals("AT-T202401010000000001", created.getTicketNo());
        assertEquals("OVER_LIMIT", created.getTriggerType());
        assertEquals(AuditTicketStatus.PENDING.name(), created.getStatus());
        assertEquals(0, new BigDecimal("553.00").compareTo(created.getOriginAmount()));

        ArgumentCaptor<AuditRecord> recordCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(recordCaptor.capture());
        assertEquals(AuditAction.SUBMIT.name(), recordCaptor.getValue().getAction());
    }

    @Test
    void enterApprovalPendingExistingIsIdempotent() {
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectOne(any())).thenReturn(ticket(1L, AuditTicketStatus.PENDING, "553.00", 0));

        service.enterApproval(task, Map.of(), 6, List.of("RISK_HIT:疑似重复报销"));

        verify(ticketMapper, never()).insert(any(AuditTicket.class));
        verify(recordMapper, never()).insert(any(AuditRecord.class));
    }

    @Test
    void enterApprovalAmendedResetsToPendingAndRefreshesReasons() {
        AgentTask task = task(100L, "T202401010000000001");
        AuditTicket amended = ticket(1L, AuditTicketStatus.AMENDED, "553.00", 1);
        when(ticketMapper.selectOne(any())).thenReturn(amended);

        service.enterApproval(task, Map.of(), 6, List.of("RULE_FAIL:部门预算超支"));

        assertEquals(AuditTicketStatus.PENDING.name(), amended.getStatus());
        // 重跑再次命中：复核原因/触发类型/风险描述必须刷新为本次命中结果（上次与本次可能不同）
        assertEquals("RULE_FAIL", amended.getTriggerType());
        assertEquals(List.of("RULE_FAIL:部门预算超支"), amended.getReviewReasons());
        assertEquals("RULE_FAIL:部门预算超支", amended.getRiskDesc());
        verify(ticketMapper).updateById(amended);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.RERUN.name(), captor.getValue().getAction());
    }

    @Test
    void enterApprovalSkipsWithdrawnTicket() {
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectOne(any())).thenReturn(ticket(1L, AuditTicketStatus.WITHDRAWN, "553.00", 0));

        service.enterApproval(task, Map.of(), 6, List.of("OVER_LIMIT:大额限额 超标"));

        verify(ticketMapper, never()).updateById(any(AuditTicket.class));
        verify(recordMapper, never()).insert(any(AuditRecord.class));
    }

    // ---------- action：权限 / 动作状态机 + 留痕 ----------

    @Test
    void actionWithoutApprovePermThrows() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, AuditTicketStatus.PENDING, "553.00", 0));
        // 无 UserContext（未登录/直连）或无 audit:approve → fail-closed 拒绝
        UserContextHolder.clear();

        BizException ex = assertThrows(BizException.class,
                () -> service.action(1L, AuditAction.APPROVE, 20L, "张三", "user", null));
        assertTrue(ex.getMessage().contains("无审批权限"));
    }

    @Test
    void actionNonPendingStatusThrows() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, AuditTicketStatus.APPROVED, "553.00", 0));

        BizException ex = assertThrows(BizException.class,
                () -> service.action(1L, AuditAction.APPROVE, 20L, "张三", "admin", null));
        assertTrue(ex.getMessage().contains("工单状态不允许"));
    }

    @Test
    void approveMarksTaskSuccessAndRecords() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectById(1L)).thenReturn(pending);
        when(taskService.getRequired(100L)).thenReturn(task);

        AuditTicketVO vo = service.action(1L, AuditAction.APPROVE, 20L, "张三", "admin", null);

        assertEquals(AuditTicketStatus.APPROVED.name(), vo.getStatus());
        verify(taskService).markSuccess(eq(task), eq(task.getResult()), eq(6));
        verify(reimbursementService).updateStatusByTaskId(100L, ReimbursementStatus.SUCCESS);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.APPROVE.name(), captor.getValue().getAction());
        assertEquals(20L, captor.getValue().getOperatorId());
        assertEquals("admin", captor.getValue().getOperatorRoles());
    }

    @Test
    void rejectMarksTaskRejectedAndRecords() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectById(1L)).thenReturn(pending);
        when(taskService.getRequired(100L)).thenReturn(task);

        AuditTicketVO vo = service.action(1L, AuditAction.REJECT, 20L, "张三", "auditor",
                new AuditActionRequest("发票不规范", null));

        assertEquals(AuditTicketStatus.REJECTED.name(), vo.getStatus());
        verify(taskService).markRejected(task);
        verify(reimbursementService).updateStatusByTaskId(100L, ReimbursementStatus.FAILED);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.REJECT.name(), captor.getValue().getAction());
        assertEquals("发票不规范", captor.getValue().getComment());
    }

    @Test
    void terminateMarksTaskTerminatedAndRecords() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectById(1L)).thenReturn(pending);
        when(taskService.getRequired(100L)).thenReturn(task);

        AuditTicketVO vo = service.action(1L, AuditAction.TERMINATE, 20L, "张三", "admin", null);

        assertEquals(AuditTicketStatus.TERMINATED.name(), vo.getStatus());
        verify(taskService).markTerminated(task);
        verify(reimbursementService).updateStatusByTaskId(100L, ReimbursementStatus.FAILED);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.TERMINATE.name(), captor.getValue().getAction());
    }

    // ---------- 提交人 resubmit 修改重跑（P3b 核心：同单续跑） ----------

    @Test
    void resubmitRecomputesTotalReplansAndRecordsSnapshot() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        List<TaskPlanStep> plan = List.of(
                new TaskPlanStep("附件OCR", "TOOL", "FILE_OCR", Map.of("reimbId", 1L)),
                new TaskPlanStep("人工复核", "LLM", null, null));
        Map<String, Object> beforeData = Map.of("totalAmount", new BigDecimal("553.00"));
        Map<String, Object> afterData = Map.of("totalAmount", new BigDecimal("500.00"));
        Map<String, Object> inputParams = Map.of("claimedTotal", new BigDecimal("500.00"), "reimbId", 1L);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(pending); // getByTask
        when(ticketMapper.selectById(1L)).thenReturn(pending);   // 锁内重读
        when(reimbursementService.buildSnapshot(1L)).thenReturn(beforeData, afterData);
        when(reimbursementService.resubmit(eq(1L), any(ReimbursementResubmitRequest.class), eq(1L)))
                .thenReturn(new ReimbursementResubmitResult(100L, inputParams, new BigDecimal("500.00")));
        when(taskService.getRequired(100L)).thenReturn(task);
        when(flowEngine.plan(task)).thenReturn(plan);

        Long taskId = service.resubmit(1L, request(), 1L, 10L, "员工一");

        assertEquals(100L, taskId);
        // 工单：AMENDED + 修正后金额 + 重跑次数自增（不动 auditorId）
        assertEquals(AuditTicketStatus.AMENDED.name(), pending.getStatus());
        assertEquals(1, pending.getRerunCount());
        assertEquals(0, new BigDecimal("500.00").compareTo(pending.getAdjustedAmount()));
        // 任务快照覆盖 + 步骤全量重规划（新入参投影）+ totalSteps 刷新
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskService).prepareRerun(eq(task), paramsCaptor.capture());
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) paramsCaptor.getValue().get("claimedTotal")));
        verify(stepService).replan(eq(100L), eq(1L), eq(plan));
        verify(taskService).markPlanned(eq(task), eq(2));
        verify(reimbursementService).resubmit(eq(1L), any(ReimbursementResubmitRequest.class), eq(1L));
        // 留痕：AMEND + 变更前后金额 + 前后快照
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.AMEND.name(), captor.getValue().getAction());
        assertEquals(0, new BigDecimal("553.00").compareTo(captor.getValue().getBeforeAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo(captor.getValue().getAfterAmount()));
        assertEquals("员工一", captor.getValue().getOperatorName());
        assertEquals(beforeData, captor.getValue().getBeforeData());
        assertEquals(afterData, captor.getValue().getAfterData());
    }

    @Test
    void resubmitRejectedStatusAllowed() {
        AuditTicket rejected = ticket(1L, AuditTicketStatus.REJECTED, "553.00", 1);
        AgentTask task = task(100L, "T202401010000000001");
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(rejected);
        when(ticketMapper.selectById(1L)).thenReturn(rejected);
        when(reimbursementService.buildSnapshot(1L)).thenReturn(Map.of());
        when(reimbursementService.resubmit(eq(1L), any(ReimbursementResubmitRequest.class), eq(1L)))
                .thenReturn(new ReimbursementResubmitResult(100L, Map.of("claimedTotal", new BigDecimal("500.00")),
                        new BigDecimal("500.00")));
        when(taskService.getRequired(100L)).thenReturn(task);
        when(flowEngine.plan(task)).thenReturn(List.of());

        service.resubmit(1L, request(), 1L, 10L, "员工一");

        assertEquals(AuditTicketStatus.AMENDED.name(), rejected.getStatus());
        assertEquals(2, rejected.getRerunCount());
    }

    @Test
    void resubmitNonOwnerThrows() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        pending.setCreatedBy(20L);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(pending);
        when(ticketMapper.selectById(1L)).thenReturn(pending);

        BizException ex = assertThrows(BizException.class,
                () -> service.resubmit(1L, request(), 1L, 10L, "员工一"));
        assertTrue(ex.getMessage().contains("仅提交人"));
    }

    @Test
    void resubmitApprovedStatusThrows() {
        AuditTicket approved = ticket(1L, AuditTicketStatus.APPROVED, "553.00", 0);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(approved);
        when(ticketMapper.selectById(1L)).thenReturn(approved);

        BizException ex = assertThrows(BizException.class,
                () -> service.resubmit(1L, request(), 1L, 10L, "员工一"));
        assertTrue(ex.getMessage().contains("仅待审批/已驳回状态"));
    }

    @Test
    void resubmitOverRerunLimitThrows() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 3);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(pending);
        when(ticketMapper.selectById(1L)).thenReturn(pending);

        BizException ex = assertThrows(BizException.class,
                () -> service.resubmit(1L, request(), 1L, 10L, "员工一"));
        assertTrue(ex.getMessage().contains("重跑上限"));
        verify(reimbursementService, never()).resubmit(any(), any(), any());
    }

    // ---------- 提交人 withdraw 撤回（PENDING → WITHDRAWN） ----------

    @Test
    void withdrawPendingCancelsTaskAndReimbAndRecords() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        task.setInputParams(paramsWithReimb(1L));
        Map<String, Object> snap = Map.of("reimbNo", "R2024010100000001");
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(pending);
        when(ticketMapper.selectById(1L)).thenReturn(pending);
        when(taskService.getRequired(100L)).thenReturn(task);
        when(reimbursementService.buildSnapshot(1L)).thenReturn(snap);

        AuditTicketVO vo = service.withdraw(1L, 10L, "员工一");

        assertEquals(AuditTicketStatus.WITHDRAWN.name(), vo.getStatus());
        verify(taskService).markCancelled(task, "提交人撤回");
        verify(reimbursementService).markCancelledByTaskId(100L);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.WITHDRAW.name(), captor.getValue().getAction());
        assertEquals(snap, captor.getValue().getBeforeData());
        assertEquals(snap, captor.getValue().getAfterData());
    }

    @Test
    void withdrawNonOwnerThrows() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        pending.setCreatedBy(20L);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(pending);
        when(ticketMapper.selectById(1L)).thenReturn(pending);

        BizException ex = assertThrows(BizException.class, () -> service.withdraw(1L, 10L, "员工一"));
        assertTrue(ex.getMessage().contains("仅提交人"));
    }

    @Test
    void withdrawNotPendingThrows() {
        AuditTicket approved = ticket(1L, AuditTicketStatus.APPROVED, "553.00", 0);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(approved);
        when(ticketMapper.selectById(1L)).thenReturn(approved);

        BizException ex = assertThrows(BizException.class, () -> service.withdraw(1L, 10L, "员工一"));
        assertTrue(ex.getMessage().contains("仅待审批状态可撤回"));
    }

    // ---------- 提交人 requestWithdraw 发起撤销（APPROVED → WITHDRAW_PENDING） ----------

    @Test
    void requestWithdrawApprovedMovesToWithdrawPending() {
        AuditTicket approved = ticket(1L, AuditTicketStatus.APPROVED, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        task.setInputParams(paramsWithReimb(1L));
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(approved);
        when(ticketMapper.selectById(1L)).thenReturn(approved);
        when(taskService.getRequired(100L)).thenReturn(task);
        when(reimbursementService.buildSnapshot(1L)).thenReturn(Map.of("reimbNo", "R2024010100000001"));

        AuditTicketVO vo = service.requestWithdraw(1L, 10L, "员工一");

        assertEquals(AuditTicketStatus.WITHDRAW_PENDING.name(), vo.getStatus());
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.WITHDRAW_REQ.name(), captor.getValue().getAction());
    }

    @Test
    void requestWithdrawIdempotentOnWithdrawPending() {
        AuditTicket withdrawPending = ticket(1L, AuditTicketStatus.WITHDRAW_PENDING, "553.00", 0);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(withdrawPending);
        when(ticketMapper.selectById(1L)).thenReturn(withdrawPending);

        service.requestWithdraw(1L, 10L, "员工一");

        // 幂等：已 WITHDRAW_PENDING 直接返回，不重复留痕（防双击）
        verify(recordMapper, never()).insert(any(AuditRecord.class));
        verify(ticketMapper, never()).updateById(any(AuditTicket.class));
    }

    @Test
    void requestWithdrawNonApprovedThrows() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        when(reimbursementService.getTaskIdByReimbId(1L)).thenReturn(100L);
        when(ticketMapper.selectOne(any())).thenReturn(pending);
        when(ticketMapper.selectById(1L)).thenReturn(pending);

        BizException ex = assertThrows(BizException.class,
                () -> service.requestWithdraw(1L, 10L, "员工一"));
        assertTrue(ex.getMessage().contains("仅已通过状态"));
    }

    // ---------- 财务撤销动作（WITHDRAW_AGREE / WITHDRAW_REFUSE，仅 WITHDRAW_PENDING） ----------

    @Test
    void agreeWithdrawCancelsTaskAndReimb() {
        AuditTicket withdrawPending = ticket(1L, AuditTicketStatus.WITHDRAW_PENDING, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectById(1L)).thenReturn(withdrawPending);
        when(taskService.getRequired(100L)).thenReturn(task);

        AuditTicketVO vo = service.action(1L, AuditAction.WITHDRAW_AGREE, 20L, "张三", "admin",
                new AuditActionRequest("同意撤销", null));

        assertEquals(AuditTicketStatus.WITHDRAWN.name(), vo.getStatus());
        verify(taskService).markCancelled(task, "财务同意撤销");
        verify(reimbursementService).markCancelledByTaskId(100L);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.WITHDRAW_AGREE.name(), captor.getValue().getAction());
    }

    @Test
    void refuseWithdrawReturnsToApproved() {
        AuditTicket withdrawPending = ticket(1L, AuditTicketStatus.WITHDRAW_PENDING, "553.00", 0);
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectById(1L)).thenReturn(withdrawPending);
        when(taskService.getRequired(100L)).thenReturn(task);

        AuditTicketVO vo = service.action(1L, AuditAction.WITHDRAW_REFUSE, 20L, "张三", "auditor",
                new AuditActionRequest("资料合规无需撤销", null));

        assertEquals(AuditTicketStatus.APPROVED.name(), vo.getStatus());
        verify(taskService, never()).markCancelled(any(AgentTask.class), anyString());
        verify(reimbursementService, never()).markCancelledByTaskId(any());
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.WITHDRAW_REFUSE.name(), captor.getValue().getAction());
    }

    @Test
    void withdrawActionOnPendingStatusThrows() {
        AuditTicket pending = ticket(1L, AuditTicketStatus.PENDING, "553.00", 0);
        when(ticketMapper.selectById(1L)).thenReturn(pending);

        BizException ex = assertThrows(BizException.class,
                () -> service.action(1L, AuditAction.WITHDRAW_AGREE, 20L, "张三", "admin", null));
        assertTrue(ex.getMessage().contains("工单状态不允许"));
    }

    // ---------- onRerunFail 重跑失败复位（防 AMENDED 死端） ----------

    @Test
    void onRerunFailResetsAmendedTicketToPending() {
        AgentTask task = task(100L, "T202401010000000001");
        AuditTicket amended = ticket(1L, AuditTicketStatus.AMENDED, "553.00", 1);
        when(ticketMapper.selectOne(any())).thenReturn(amended);

        service.onRerunFail(task);

        assertEquals(AuditTicketStatus.PENDING.name(), amended.getStatus());
        verify(ticketMapper).updateById(amended);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.RERUN_FAILED.name(), captor.getValue().getAction());
    }

    @Test
    void onRerunFailSkipsNonAmendedTicket() {
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectOne(any())).thenReturn(ticket(1L, AuditTicketStatus.PENDING, "553.00", 0));

        service.onRerunFail(task);

        verify(ticketMapper, never()).updateById(any(AuditTicket.class));
        verify(recordMapper, never()).insert(any(AuditRecord.class));
    }

    @Test
    void onRerunFailSkipsGenericTask() {
        AgentTask task = task(100L, "T202401010000000001");
        task.setTaskType("GENERIC");

        service.onRerunFail(task);

        verify(ticketMapper, never()).selectOne(any());
    }

    // ---------- closeOnAutoPass ----------

    @Test
    void closeOnAutoPassClosesAmendedTicket() {
        AgentTask task = task(100L, "T202401010000000001");
        AuditTicket amended = ticket(1L, AuditTicketStatus.AMENDED, "553.00", 1);
        when(ticketMapper.selectOne(any())).thenReturn(amended);

        service.closeOnAutoPass(task);

        assertEquals(AuditTicketStatus.APPROVED.name(), amended.getStatus());
        verify(ticketMapper).updateById(amended);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(AuditAction.APPROVE.name(), captor.getValue().getAction());
        assertTrue(captor.getValue().getComment().contains("自动通过"));
        assertEquals(null, captor.getValue().getOperatorName());
    }

    @Test
    void closeOnAutoPassSkipsPendingTicket() {
        AgentTask task = task(100L, "T202401010000000001");
        when(ticketMapper.selectOne(any())).thenReturn(ticket(1L, AuditTicketStatus.PENDING, "553.00", 0));

        service.closeOnAutoPass(task);

        verify(ticketMapper, never()).updateById(any(AuditTicket.class));
        verify(recordMapper, never()).insert(any(AuditRecord.class));
    }

    // ---------- 构造辅助 ----------

    private static AgentTask task(Long taskId, String taskNo) {
        AgentTask t = new AgentTask();
        t.setId(taskId);
        t.setTenantId(1L);
        t.setTaskNo(taskNo);
        t.setTitle("测试报销");
        t.setTaskType("REIMBURSEMENT");
        t.setCreatedBy(10L);
        t.setStatus("APPROVAL_PENDING");
        t.setFinishedSteps(6);
        t.setInputParams(paramsWithReimb(null));
        t.setResult(Map.of("summary", "审核流程执行完成"));
        return t;
    }

    private static Map<String, Object> paramsWithReimb(Long reimbId) {
        Map<String, Object> params = new HashMap<>();
        params.put("claimedTotal", new BigDecimal("553.00"));
        if (reimbId != null) {
            params.put("reimbId", reimbId);
        }
        return params;
    }

    private static AuditTicket ticket(Long id, AuditTicketStatus status, String origin, int rerun) {
        AuditTicket t = new AuditTicket();
        t.setId(id);
        t.setTenantId(1L);
        t.setTaskId(100L);
        t.setTicketNo("AT-T202401010000000001");
        t.setTitle("测试报销");
        t.setTriggerType("OVER_LIMIT");
        t.setRiskDesc("大额限额 超标");
        t.setOriginAmount(new BigDecimal(origin));
        t.setStatus(status.name());
        t.setRerunCount(rerun);
        t.setCreatedBy(10L);
        return t;
    }

    private static ReimbursementItemRequest item(String name, String amount) {
        return new ReimbursementItemRequest(name, new BigDecimal(amount), null, null, null, null,
                null, null, null, null, null);
    }

    private static ReimbursementResubmitRequest request() {
        return new ReimbursementResubmitRequest("TRAVEL", LocalDate.of(2026, 8, 1), "改低金额",
                List.of(item("住宿", "400.00"), item("高铁", "100.00")), List.of(1L, 2L));
    }
}
