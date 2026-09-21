package com.finaudit.agentcore.service;

import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.agentcore.pojo.vo.TaskProgressVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 任务进度与预计等待时间单测（P3.8 R8-3）。
 *
 * <p>这类"展示型计算"最容易被写成"看起来对"：百分比口径、终态是否还估算、
 * 无历史样本时怎么办——都必须有断言钉住，否则前端会收到自相矛盾的字段组合
 * （例如已 SUCCESS 却还给"预计剩余 30 秒"）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskProgressServiceTest {

    @Mock
    private AgentTaskStepService stepService;

    private TaskProgressService service() {
        return new TaskProgressService(stepService);
    }

    private static AgentTask task(String status) {
        AgentTask t = new AgentTask();
        t.setId(100L);
        t.setTaskNo("T202601010000000001");
        t.setStatus(status);
        t.setTotalSteps(8);
        t.setFinishedSteps(3);
        t.setStartedAt(LocalDateTime.now().minusSeconds(10));
        return t;
    }

    private static AgentTaskStep step(int no, String type, String tool, String role, String status) {
        AgentTaskStep s = new AgentTaskStep();
        s.setId((long) no);
        s.setStepNo(no);
        s.setStepName("步骤" + no);
        s.setStepType(type);
        s.setToolName(tool);
        s.setAgentRole(role);
        s.setStatus(status);
        return s;
    }

    private void givenSteps(List<AgentTaskStep> steps) {
        when(stepService.listByTask(100L)).thenReturn(steps);
    }

    /** 运行中 + 有历史样本：按同类步骤平均耗时累加，并标注来源为 HISTORY */
    @Test
    void runningTaskEstimatesFromHistoryWhenSamplesExist() {
        givenSteps(List.of(
                step(1, "TOOL", "ocr_extract", null, "SUCCESS"),
                step(2, "TOOL", "budget_query", null, "SUCCESS"),
                step(3, "TOOL", "amount_verify", null, "SUCCESS"),
                step(4, "TOOL", "rule_check", null, "RUNNING"),
                step(5, "TOOL", "duplicate_check", null, "PENDING"),
                step(6, "LLM", null, "RISK_AUDITOR", "PENDING")));
        when(stepService.avgDurationByStepKey(any())).thenReturn(List.of(
                Map.of("stepType", "TOOL", "toolName", "rule_check", "agentRole", "", "avgMs", 100.0, "samples", 5),
                Map.of("stepType", "TOOL", "toolName", "duplicate_check", "agentRole", "", "avgMs", 200.0, "samples", 5),
                Map.of("stepType", "LLM", "toolName", "", "agentRole", "RISK_AUDITOR", "avgMs", 3000.0, "samples", 4)));

        TaskProgressVO vo = service().progress(task(TaskStatus.RUNNING.name()));

        assertEquals(6, vo.getTotalSteps(), "步骤数取自步骤列表");
        assertEquals(3, vo.getFinishedSteps());
        assertEquals(50.0d, vo.getProgressPct(), "3/6 = 50%");
        assertEquals("步骤4", vo.getCurrentStepName(), "RUNNING 步骤优先作为当前步骤");
        // 剩 3 步：rule_check 100 + duplicate_check 200 + LLM 3000
        assertEquals(3300L, vo.getEstimatedRemainingMs());
        assertEquals("HISTORY", vo.getEstimateSource());
        assertTrue(vo.getSamples() >= 1);
        assertTrue(vo.getMessage().contains("第 4/6 步"), "提示应能定位到当前步骤，实际=" + vo.getMessage());
        assertTrue(vo.getEstimatedTotalMs() >= vo.getElapsedMs(), "预计总耗时不得小于已耗时");
    }

    /** 无历史样本：退化为缺省单步耗时，并如实标注 DEFAULT（前端据此提示"粗略估算"） */
    @Test
    void fallsBackToDefaultWhenNoSamples() {
        givenSteps(List.of(
                step(1, "TOOL", "ocr_extract", null, "SUCCESS"),
                step(2, "TOOL", "rule_check", null, "PENDING"),
                step(3, "LLM", null, "SCHEDULER", "PENDING")));
        when(stepService.avgDurationByStepKey(any())).thenReturn(List.of());

        TaskProgressVO vo = service().progress(task(TaskStatus.RUNNING.name()));

        assertEquals("DEFAULT", vo.getEstimateSource());
        assertEquals(0, vo.getSamples());
        // 缺省：TOOL 1500 + LLM 2500
        assertEquals(4000L, vo.getEstimatedRemainingMs());
    }

    /** 终态：不再估算（剩余 0），耗时即实际值，依据标注 FIXED */
    @Test
    void terminalTaskReportsActualDurationWithoutEstimate() {
        givenSteps(List.of(
                step(1, "TOOL", "ocr_extract", null, "SUCCESS"),
                step(2, "LLM", null, "SCHEDULER", "SUCCESS")));
        AgentTask t = task(TaskStatus.APPROVAL_PENDING.name());
        t.setDurationMs(12345L);

        TaskProgressVO vo = service().progress(t);

        assertEquals(100.0d, vo.getProgressPct(), "全部步骤成功 → 100%");
        assertEquals(12345L, vo.getElapsedMs(), "终态取落库的实际耗时");
        assertEquals(0L, vo.getEstimatedRemainingMs(), "终态不应再给预计剩余");
        assertEquals("FIXED", vo.getEstimateSource());
        assertTrue(vo.getMessage().contains("已完成"), "实际=" + vo.getMessage());
    }

    /** 步骤尚未生成（刚提交）：进度 0%，且不因除零报错 */
    @Test
    void taskWithoutStepsIsSafe() {
        givenSteps(new ArrayList<>());
        AgentTask t = task(TaskStatus.PENDING.name());
        t.setTotalSteps(0);

        TaskProgressVO vo = service().progress(t);

        assertEquals(0, vo.getTotalSteps());
        assertEquals(0.0d, vo.getProgressPct());
        assertNotNull(vo.getStatusText());
        assertNotNull(vo.getMessage());
    }

    /** 基线查询失败（统计表异常等）不得让进度接口报错：退化为缺省值即可 */
    @Test
    void baselineQueryFailureDoesNotBreakProgress() {
        givenSteps(List.of(step(1, "TOOL", "rule_check", null, "PENDING")));
        // 步骤服务内部已兜底，这里模拟它返回空（等价于"查不到基线"）
        when(stepService.avgDurationByStepKey(any())).thenReturn(List.of());

        TaskProgressVO vo = service().progress(task(TaskStatus.RUNNING.name()));

        assertEquals("DEFAULT", vo.getEstimateSource());
        assertNotNull(vo.getEstimatedRemainingMs());
    }

    /** 待审态属于"流水线本次执行已结束"：人工等待不计入，故不再估算 */
    @Test
    void approvalPendingCountsAsPipelineFinished() {
        givenSteps(List.of(step(1, "LLM", null, "SCHEDULER", "SUCCESS")));
        AgentTask t = task(TaskStatus.APPROVAL_PENDING.name());
        t.setDurationMs(9000L);

        TaskProgressVO vo = service().progress(t);

        assertEquals(0L, vo.getEstimatedRemainingMs());
        assertEquals(9000L, vo.getElapsedMs());
    }

    /**
     * 纠错次数必须透出：R8-3 运行时实测发现自校验纠错会重跑风险步骤，
     * 步骤状态从 SUCCESS 复位 → finishedSteps 减少 → 百分比**回退**（实测 87.5%→62.5%）。
     * 回退是真实状态（端点不得谎报单调递增），但前端需要 correctionCount 才能解释它。
     */
    @Test
    void correctionCountIsExposed() {
        givenSteps(List.of(
                step(1, "TOOL", "ocr_extract", null, "SUCCESS"),
                step(2, "TOOL", "duplicate_check", null, "RUNNING"),
                step(3, "LLM", null, "RISK_AUDITOR", "PENDING")));
        when(stepService.avgDurationByStepKey(any())).thenReturn(List.of());
        AgentTask t = task(TaskStatus.RUNNING.name());
        t.setCorrectionCount(1);

        TaskProgressVO vo = service().progress(t);

        assertEquals(1, vo.getCorrectionCount(), "纠错次数透出（前端据此提示「正在重新核验」而非让进度条莫名倒退）");
        assertEquals(33.3d, vo.getProgressPct(), "1/3 ≈ 33.3%（回退后的真实值）");
    }

    /** 纠错次数为 NULL（历史任务/未走过纠错）不得传 null 给前端，统一归 0 */
    @Test
    void correctionCountNullBecomesZero() {
        givenSteps(List.of(step(1, "TOOL", "ocr_extract", null, "SUCCESS")));
        when(stepService.avgDurationByStepKey(any())).thenReturn(List.of());

        TaskProgressVO vo = service().progress(task(TaskStatus.RUNNING.name()));

        assertEquals(0, vo.getCorrectionCount());
    }
}
