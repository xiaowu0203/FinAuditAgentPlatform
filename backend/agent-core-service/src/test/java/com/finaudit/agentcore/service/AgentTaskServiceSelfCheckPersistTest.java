package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.finaudit.agentcore.mapper.AgentTaskMapper;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自校验结果落库单测（P3.8 R5-4 / R5-9）。
 *
 * <p>背景：运行时任务卡 RUNNING、{@code self_check_result} 为 NULL，随后又出现
 * 「{@code self_check_result} 有值、{@code correction_count} 恒为 0」的诡异组合。
 * 最终定位为 JSON 列的写入方式错误（wrapper.set 不带 typeHandler），本测试同时覆盖
 * {@code applySelfCheckResult} / {@code prepareRerun} / {@code incrementCorrectionCount}
 * 三个落库方法，并钉死「JSON 列必须走实体补丁」这条纪律。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskServiceSelfCheckPersistTest {

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private TaskEventPublisher eventPublisher;
    @Mock
    private AgentTaskStepService stepService;

    private AgentTaskService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentTask.class);
    }

    private void newService() {
        service = new AgentTaskService(taskMapper, eventPublisher, stepService);
    }

    private static AgentTask task() {
        AgentTask t = new AgentTask();
        t.setId(100L);
        t.setTenantId(1L);
        t.setTaskNo("T202601010000000001");
        return t;
    }

    private static Map<String, Object> selfCheckMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coherent", false);
        m.put("checkedCount", 5);
        m.put("hallucination", false);
        m.put("contradictions", List.of(Map.of(
                "assertion", "DUPLICATE_HIGH_VS_HIGH_CONFIDENCE",
                "type", "CONTRADICTION",
                "detail", "发票号硬命中重复报销，但风控置信度高达 0.95")));
        return m;
    }

    @Test
    void applySelfCheckResultUsesEntityPatchWithTypeHandler() {
        newService();
        when(taskMapper.update(any(), any())).thenReturn(1);

        AgentTask t = task();
        boolean ok = service.applySelfCheckResult(t, selfCheckMap());

        assertEquals(true, ok, "落库应返回 true");
        assertNotNull(t.getSelfCheckResult(), "实体内存字段应同步（供同事务后续逻辑读取）");

        // ⚠️ R5-9：JSON 列必须经【实体补丁】写入（字段带 typeHandler），
        //    不能放进 wrapper 的 set —— wrapper 参数不带 typeHandler，
        //    MySQL 5.7 会报 "Cannot create a JSON value from a string with CHARACTER SET 'binary'"，
        //    而该异常曾被自校验兜底 catch 吞掉，导致自纠错静默失效。
        ArgumentCaptor<AgentTask> entity = ArgumentCaptor.forClass(AgentTask.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskMapper).update(entity.capture(), wrapper.capture());

        assertNotNull(entity.getValue(), "必须传实体补丁（否则 JSON 列拿不到 typeHandler）");
        assertEquals(100L, entity.getValue().getId(), "补丁只带主键");
        assertNotNull(entity.getValue().getSelfCheckResult(), "补丁必须带上自校验结果");
        assertNull(entity.getValue().getStatus(), "补丁不得夹带其他字段（避免脏写）");
        assertFalse(String.valueOf(wrapper.getValue().getSqlSet()).contains("self_check_result"),
                "JSON 列不得出现在 wrapper 的 SET 片段里（R5-9 根因）");
        assertTrue(String.valueOf(wrapper.getValue().getSqlSet()).isEmpty()
                        || wrapper.getValue().getSqlSet() == null,
                "本方法无需 wrapper 侧 SET");
    }

    /**
     * amend 重跑链路（{@code prepareRerun}）同样受 R5-9 影响：它原先用
     * {@code set(inputParams, map)} 写 JSON 列，实测会抛 {@code MysqlDataTruncation}，
     * 即「工单驳回 → 修改后重跑」一直会直接失败。此处钉死修复后的形态。
     */
    @Test
    void prepareRerunUsesEntityPatchForInputParams() {
        newService();
        when(taskMapper.update(any(), any())).thenReturn(1);

        AgentTask t = task();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("items", List.of(Map.of("amount", 100)));
        boolean ok = service.prepareRerun(t, params);

        assertEquals(true, ok);
        assertEquals(params, t.getInputParams(), "内存入参应同步");

        ArgumentCaptor<AgentTask> entity = ArgumentCaptor.forClass(AgentTask.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskMapper).update(entity.capture(), wrapper.capture());

        assertNotNull(entity.getValue().getInputParams(), "JSON 入参必须走实体补丁");
        assertFalse(String.valueOf(wrapper.getValue().getSqlSet()).contains("input_params"),
                "JSON 列不得出现在 wrapper 的 SET 片段里（R5-9 根因）");
        // 置空列仍走 wrapper 的 set(col, null)（entity 的 null 字段不会进 SET 子句）
        assertTrue(String.valueOf(wrapper.getValue().getSqlSet()).contains("result"),
                "清空 result 仍需 wrapper 显式写 NULL");
    }

    @Test
    void incrementCorrectionCountReadsBackFreshValue() {
        newService();
        AgentTask t = task();
        AgentTask fresh = task();
        fresh.setCorrectionCount(3);
        when(taskMapper.selectById(100L)).thenReturn(fresh);

        int n = service.incrementCorrectionCount(t);

        assertEquals(3, n, "应回读自增后的值");
        assertEquals(3, t.getCorrectionCount(), "内存字段应同步");
    }

    @Test
    void agentTaskTableInfoKnowsSelfCheckFields() {
        TableInfo info = TableInfoHelper.getTableInfo(AgentTask.class);
        assertNotNull(info);
        assertNotNull(info.getFieldList().stream()
                        .filter(f -> "selfCheckResult".equals(f.getProperty()))
                        .findFirst().orElse(null),
                "selfCheckResult 必须被 TableInfo 识别（否则 wrapper 无法映射列）");
        assertNotNull(info.getFieldList().stream()
                        .filter(f -> "correctionCount".equals(f.getProperty()))
                        .findFirst().orElse(null),
                "correctionCount 必须被 TableInfo 识别");
    }
}
