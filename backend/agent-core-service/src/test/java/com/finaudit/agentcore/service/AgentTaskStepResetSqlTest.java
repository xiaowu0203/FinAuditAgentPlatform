package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.finaudit.agentcore.enums.StepStatus;
import com.finaudit.agentcore.mapper.AgentTaskStepMapper;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自纠错步骤重置与提示注入的 SQL 构造验证（P3.8 R5）。
 *
 * <p>背景：运行时 {@code self_check_result} 已落库、任务也正常收尾，但 {@code correction_count} 仍为 0、
 * 工单原因里没有「自校验未通过」。当时的怀疑方向是 {@code resetForSelfCorrection} 的 wrapper 构造
 * （含 {@code set(col, null)}）——<b>实测该方向是错的</b>：真正的原因是
 * {@code applySelfCheckResult} 用 wrapper.set 写 JSON 列必抛异常（R5-9），
 * 于是整段自纠错动作根本没被执行。本类保留重置相关护栏，JSON 列的护栏见
 * {@link JsonColumnTypeHandlerGuardTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskStepResetSqlTest {

    @Mock
    private AgentTaskStepMapper stepMapper;

    private AgentTaskStepService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentTaskStep.class);
    }

    private static AgentTaskStep step(Long id, int no, String role) {
        AgentTaskStep s = new AgentTaskStep();
        s.setId(id);
        s.setTaskId(1L);
        s.setStepNo(no);
        s.setStepName("步骤" + no);
        s.setStepType("LLM");
        s.setAgentRole(role);
        s.setStatus(StepStatus.SUCCESS.name());
        return s;
    }

    @Test
    void resetBuildsValidSqlWithExplicitNullSet() {
        service = new AgentTaskStepService(stepMapper);
        when(stepMapper.update(any(), any())).thenReturn(1);

        int n = assertDoesNotThrow(() -> service.resetForSelfCorrection(List.of(
                        step(7L, 7, "RISK_AUDITOR"), step(8L, 8, "SCHEDULER"))),
                "重置步骤不得抛异常（含 set(col,null) 的 wrapper 构造）");

        assertEquals(2, n, "两个步骤都应被重置");
    }

    /**
     * 回归护栏（R5-8，根因已于 R5-9 修正）：重置必须<b>同步内存对象</b>。
     *
     * <p>只改 DB 不同步内存时，调用方（{@code AgentOrchestrator.finalizeSuccess}）持有的
     * {@code steps} 列表里仍是 {@code SUCCESS}，下游判定的都是过期状态。
     * 注：R5-8 当时误把「自纠错从不触发」归因于此，真实根因是 R5-9（JSON 列写入方式错误，
     * 见 {@link JsonColumnTypeHandlerGuardTest}）；内存同步本身仍是必要的一致性保证。</p>
     */
    @Test
    void resetSyncsInMemoryStepState() {
        service = new AgentTaskStepService(stepMapper);
        when(stepMapper.update(any(), any())).thenReturn(1);

        AgentTaskStep risk = step(7L, 7, "RISK_AUDITOR");
        AgentTaskStep summary = step(8L, 8, "SCHEDULER");
        risk.setOutput(java.util.Map.of("decision", "APPROVE"));
        risk.setErrorMsg("旧错误");
        risk.setRetryCount(2);

        service.resetForSelfCorrection(List.of(risk, summary));

        // 内存对象必须与 DB 一致：状态转 PENDING、输出/错误清空、重试归零
        assertEquals(StepStatus.PENDING.name(), risk.getStatus(), "内存状态必须同步为 PENDING");
        assertEquals(StepStatus.PENDING.name(), summary.getStatus());
        assertNull(risk.getOutput(), "内存输出必须清空（否则重跑会读到旧结论）");
        assertNull(risk.getErrorMsg());
        assertEquals(0, risk.getRetryCount());
    }

    @Test
    void resetSkipsRunningSteps() {
        // 仅重置 SUCCESS/FAILED；RUNNING 有在途执行，wrapper 的 in(...) 使其不命中
        service = new AgentTaskStepService(stepMapper);
        when(stepMapper.update(any(), any())).thenReturn(0);   // DB 未命中（RUNNING）

        AgentTaskStep running = step(7L, 7, "RISK_AUDITOR");
        running.setStatus(StepStatus.RUNNING.name());

        int n = service.resetForSelfCorrection(List.of(running));

        assertEquals(0, n, "RUNNING 步骤不应被重置（DB 未命中）");
    }

    @Test
    void resetWrapperSqlSetIsNotEmpty() {
        // 直接验证 wrapper 形态：SET 子句必须非空且能解析出列名
        LambdaUpdateWrapper<AgentTaskStep> w = new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, 7L)
                .in(AgentTaskStep::getStatus, StepStatus.SUCCESS.name(), StepStatus.FAILED.name())
                .set(AgentTaskStep::getStatus, StepStatus.PENDING.name())
                .set(AgentTaskStep::getOutput, null)
                .set(AgentTaskStep::getErrorMsg, null)
                .set(AgentTaskStep::getRetryCount, 0);

        assertNotNull(w.getSqlSet(), "SET 子句不得为空");
        assertDoesNotThrow(() -> w.getTargetSql(), "列名解析不应抛异常");
    }

    @Test
    void resetEmptyListReturnsZero() {
        service = new AgentTaskStepService(stepMapper);
        assertEquals(0, service.resetForSelfCorrection(List.of()));
        assertEquals(0, service.resetForSelfCorrection(null));
    }

    /**
     * 注入矛盾提示必须走<b>实体补丁</b>（R5-9）：{@code input_params} 是 JSON 列，
     * wrapper 的 {@code set(col, map)} 不带 typeHandler，实测抛
     * {@code Cannot create a JSON value from a string with CHARACTER SET 'binary'}，
     * 提示注入会静默失败（被自校验兜底 catch 吞掉）。
     */
    @Test
    void updateInputParamsUsesEntityPatchWithTypeHandler() {
        service = new AgentTaskStepService(stepMapper);
        when(stepMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("selfCheckHint", "【自校验发现矛盾，请重新判断】");
        boolean ok = service.updateInputParams(7L, params);

        assertEquals(true, ok, "注入应返回 true");

        ArgumentCaptor<AgentTaskStep> entity = ArgumentCaptor.forClass(AgentTaskStep.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(stepMapper).update(entity.capture(), wrapper.capture());

        assertNotNull(entity.getValue(), "必须传实体补丁（否则 JSON 列拿不到 typeHandler）");
        assertEquals(7L, entity.getValue().getId());
        assertEquals(params, entity.getValue().getInputParams(), "补丁必须带上入参");
        assertFalse(String.valueOf(wrapper.getValue().getSqlSet()).contains("input_params"),
                "JSON 列不得出现在 wrapper 的 SET 片段里（R5-9 根因）");
    }

    /**
     * 与 {@link JsonColumnTypeHandlerGuardTest} 配套的反面证据：把 JSON 列放进 wrapper 的 SET
     * 片段是「看起来正常、实际必然失败」的写法——本类早先的排查方向就错在这里。
     */
    @Test
    void wrapperSetOnInputParamsIsTheTrap() {
        LambdaUpdateWrapper<AgentTaskStep> w = new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, 7L)
                .set(AgentTaskStep::getInputParams, java.util.Map.of("reimbId", 73L));

        assertNotNull(w.getSqlSet(), "SET 片段非空");
        assertTrue(w.getSqlSet().contains("input_params"),
                "wrapper 确实会拼出列名——正因为 SQL 看起来完全正确，这个坑才难发现");
    }
}
