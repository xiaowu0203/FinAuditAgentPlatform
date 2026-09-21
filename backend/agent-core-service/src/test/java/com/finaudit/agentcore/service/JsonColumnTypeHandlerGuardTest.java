package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.mapper.AgentTaskMapper;
import com.finaudit.agentcore.mapper.AgentTaskStepMapper;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 列写入方式的回归护栏（P3.8 R5-9）。
 *
 * <p><b>为什么需要这个测试</b>：R5 的自纠错链路连续三轮「看起来跑通了但实际没触发」，
 * 根因是 {@code LambdaUpdateWrapper.set(jsonColumn, value)} 生成的参数<b>不携带 typeHandler</b>，
 * MyBatis 只能把 {@code Map} 当未知对象交给 JDBC 驱动，驱动按 binary 字符集发送字符串，
 * MySQL 5.7 直接拒绝：</p>
 *
 * <pre>Data truncation: Cannot create a JSON value from a string with CHARACTER SET 'binary'</pre>
 *
 * <p>而异常又被自校验的兜底 catch（防止自校验阻断收尾）吞掉，于是表现为
 * 「{@code self_check_result} 有值、{@code correction_count} 恒为 0」这种极难定位的组合。
 * 本机实测：wrapper.set 写 {@code agent_task.self_check_result} / {@code agent_task_step.input_params}
 * 均抛该异常；改成<b>实体补丁更新</b>（字段带 {@link JacksonTypeHandler}）即成功。</p>
 *
 * <p>因此本测试在<b>不连数据库</b>的前提下，用 MyBatis 真实解析出的 SQL 断言这条纪律：
 * JSON 列必须出现在「带 typeHandler 的实体参数」里，绝不允许出现在 wrapper 的 SET 片段里。</p>
 */
class JsonColumnTypeHandlerGuardTest {

    private static MybatisConfiguration configuration;

    @BeforeAll
    static void init() {
        configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, AgentTask.class);
        TableInfoHelper.initTableInfo(assistant, AgentTaskStep.class);
        configuration.addMapper(AgentTaskMapper.class);
        configuration.addMapper(AgentTaskStepMapper.class);
    }

    private static BoundSql boundSql(Class<?> mapper, Object parameter) {
        MappedStatement ms = configuration.getMappedStatement(mapper.getName() + ".update");
        assertNotNull(ms, "update 语句必须已注入（否则测试口径失效）");
        return ms.getBoundSql(parameter);
    }

    private static Map<String, Object> updateParam(Object entity, Object wrapper) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("et", entity);
        pm.put("ew", wrapper);
        return pm;
    }

    /**
     * 找出 SQL 里针对某属性（{@code et.selfCheckResult} 形态）的参数映射。
     * <p>注意 {@code ParameterMapping.property} 是<b>属性名</b>（驼峰），而列名在下划线形态的 SQL 文本里，
     * 故两者要分别断言。</p>
     */
    private static ParameterMapping mappingOf(BoundSql bs, String property) {
        return bs.getParameterMappings().stream()
                .filter(p -> p.getProperty().endsWith(property))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("实体补丁更新 self_check_result：参数必须带 JacksonTypeHandler")
    void entityPatchCarriesTypeHandlerForSelfCheckResult() {
        AgentTask patch = AgentTask.selfCheckResultPatch(100L, Map.of("coherent", false, "checkedCount", 5));
        BoundSql bs = boundSql(AgentTaskMapper.class, updateParam(patch, new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, 100L)));

        ParameterMapping pm = mappingOf(bs, "selfCheckResult");
        assertNotNull(pm, "SQL 里必须出现 self_check_result 的赋值（否则等于没写）");
        assertTrue(bs.getSql().contains("self_check_result"), "SQL 文本应含该列");
        assertTrue(pm.getTypeHandler() instanceof JacksonTypeHandler,
                "JSON 列参数必须由 JacksonTypeHandler 序列化，实际: "
                        + (pm.getTypeHandler() == null ? "null" : pm.getTypeHandler().getClass().getName()));
    }

    @Test
    @DisplayName("实体补丁更新 input_params（步骤）：参数必须带 JacksonTypeHandler")
    void entityPatchCarriesTypeHandlerForStepInputParams() {
        AgentTaskStep patch = AgentTaskStep.inputParamsPatch(7L, Map.of("selfCheckHint", "x"));
        BoundSql bs = boundSql(AgentTaskStepMapper.class, updateParam(patch, new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getId, 7L)));

        ParameterMapping pm = mappingOf(bs, "inputParams");
        assertNotNull(pm, "SQL 里必须出现 input_params 的赋值");
        assertTrue(bs.getSql().contains("input_params"), "SQL 文本应含该列");
        assertTrue(pm.getTypeHandler() instanceof JacksonTypeHandler,
                "JSON 列参数必须由 JacksonTypeHandler 序列化，实际: "
                        + (pm.getTypeHandler() == null ? "null" : pm.getTypeHandler().getClass().getName()));
    }

    /**
     * 反面证据：wrapper 的 {@code set(jsonColumn, value)} 生成的参数<b>没有</b> typeHandler。
     *
     * <p>这正是 R5-9 的根因，也是本测试存在的理由——把这个「看起来完全正常」的写法钉死在测试里，
     * 任何人再写出 {@code .set(AgentTask::getSelfCheckResult, map)} 都会被这条断言拦住。</p>
     */
    @Test
    @DisplayName("反面证据：wrapper.set 写 JSON 列不带 typeHandler（MySQL 5.7 会拒绝）")
    void wrapperSetLacksTypeHandlerForJsonColumn() {
        LambdaUpdateWrapper<AgentTask> wrapper = new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, 100L)
                .set(AgentTask::getSelfCheckResult, Map.of("coherent", false));

        assertNotNull(wrapper.getSqlSet(), "wrapper 的 SET 片段非空");
        assertTrue(wrapper.getSqlSet().contains("self_check_result"), "SET 片段含该列");

        BoundSql bs = boundSql(AgentTaskMapper.class, updateParam(null, wrapper));
        ParameterMapping pm = bs.getParameterMappings().stream()
                .filter(p -> p.getProperty().startsWith("ew.paramNameValuePairs"))
                .findFirst()
                .orElse(null);
        assertNotNull(pm, "wrapper 参数应落在 paramNameValuePairs");
        assertFalse(pm.getTypeHandler() instanceof JacksonTypeHandler,
                "wrapper.set 的 JSON 列参数不得被当作已具备 typeHandler——若此断言失败，"
                        + "说明 MyBatis-Plus 行为已变，可考虑放宽本护栏");
    }

    /** 表结构层面确认：这些列在 TableInfo 里确实登记了 typeHandler（护栏成立的前提）。 */
    @Test
    @DisplayName("TableInfo 确认 JSON 列已登记 JacksonTypeHandler")
    void tableInfoRegistersJacksonTypeHandler() {
        TableInfo task = TableInfoHelper.getTableInfo(AgentTask.class);
        TableInfo step = TableInfoHelper.getTableInfo(AgentTaskStep.class);
        assertNotNull(task);
        assertNotNull(step);
        assertEquals(JacksonTypeHandler.class, task.getFieldList().stream()
                        .filter(f -> "selfCheckResult".equals(f.getProperty()))
                        .findFirst().orElseThrow().getTypeHandler(),
                "agent_task.self_check_result 必须登记 JacksonTypeHandler");
        assertEquals(JacksonTypeHandler.class, step.getFieldList().stream()
                        .filter(f -> "inputParams".equals(f.getProperty()))
                        .findFirst().orElseThrow().getTypeHandler(),
                "agent_task_step.input_params 必须登记 JacksonTypeHandler");
    }
}
