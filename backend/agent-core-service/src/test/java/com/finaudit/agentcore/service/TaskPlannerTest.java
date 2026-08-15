package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.model.client.ChatReply;
import com.finaudit.starter.model.client.TokenUsage;
import com.finaudit.starter.web.feign.ToolServiceFeign;
import com.finaudit.starter.web.feign.dto.ToolInfo;
import com.finaudit.starter.web.result.R;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务规划器单测：AiClient 以 CALLS_REAL_METHODS mock，跑真实的
 * {@link AiClient#chatStructured} 默认实现（BeanOutputConverter 解析），
 * 验证结构化解析成功与失败回退内置模板两条路径。
 */
class TaskPlannerTest {

    private final ToolServiceFeign feign = mock(ToolServiceFeign.class);
    private final AiClient aiClient = mock(AiClient.class, CALLS_REAL_METHODS);
    private final ChatClientFactory factory = mock(ChatClientFactory.class);

    private TaskPlanner newPlanner() {
        when(factory.getClient(ModelType.DEEPSEEK)).thenReturn(aiClient);
        return new TaskPlanner(factory, feign);
    }

    private AgentTask taskWithInputs() {
        AgentTask task = new AgentTask();
        task.setTenantId(1L);
        task.setTitle("报销审核");
        task.setTaskType(TaskType.REIMBURSEMENT.name());
        task.setInputParams(Map.of(
                "items", List.of(Map.of("name", "打车费", "amount", 100)),
                "claimedTotal", 100));
        return task;
    }

    private void stubToolCatalog() {
        when(feign.listEnabled(anyLong())).thenReturn(R.success(List.of(
                new ToolInfo("amount_verify", "金额核验工具", "加总明细金额并与申报总额比对", null))));
    }

    @Test
    void plan_parsesStructuredStepsOnSuccess() {
        stubToolCatalog();
        when(aiClient.chatWithUsage(anyString(), anyString())).thenReturn(new ChatReply("""
                [{"stepName":"金额核验","stepType":"TOOL","toolName":"amount_verify","inputParams":{}},
                 {"stepName":"审核结论汇总","stepType":"LLM","toolName":null,"inputParams":null}]
                """, TokenUsage.ZERO));

        List<TaskPlanStep> steps = newPlanner().plan(taskWithInputs());

        assertNotNull(steps);
        assertEquals(2, steps.size());
        assertEquals("金额核验", steps.get(0).stepName());
        assertEquals("TOOL", steps.get(0).stepType());
        assertEquals("amount_verify", steps.get(0).toolName());
        assertEquals("审核结论汇总", steps.get(1).stepName());
        assertEquals("LLM", steps.get(1).stepType());
    }

    @Test
    void plan_fallsBackToTemplateWhenModelThrows() {
        stubToolCatalog();
        when(aiClient.chatWithUsage(anyString(), anyString()))
                .thenThrow(new RuntimeException("model down"));

        List<TaskPlanStep> steps = newPlanner().plan(taskWithInputs());

        assertEquals(2, steps.size());
        assertEquals("金额核验", steps.get(0).stepName());
        assertEquals("TOOL", steps.get(0).stepType());
        assertEquals("amount_verify", steps.get(0).toolName());
        assertEquals("审核结论汇总", steps.get(1).stepName());
    }

    @Test
    void plan_fallsBackToTemplateWhenListEmpty() {
        stubToolCatalog();
        when(aiClient.chatWithUsage(anyString(), anyString())).thenReturn(new ChatReply("[]", TokenUsage.ZERO));

        List<TaskPlanStep> steps = newPlanner().plan(taskWithInputs());

        assertEquals(2, steps.size());
        assertEquals("金额核验", steps.get(0).stepName());
        assertEquals("amount_verify", steps.get(0).toolName());
    }

    @Test
    void filterTools_convergesByBusiness() {
        List<ToolInfo> tools = List.of(new ToolInfo("amount_verify", "金额核验工具", "desc", null));

        assertEquals(1, TaskPlanner.filterTools(TaskType.REIMBURSEMENT, tools).size());
        assertEquals(0, TaskPlanner.filterTools(TaskType.GENERIC, tools).size());
    }

    @Test
    void plan_reimbursementTask_injectsFinanceTools() {
        stubToolCatalog();
        when(aiClient.chatWithUsage(anyString(), anyString())).thenReturn(new ChatReply("[]", TokenUsage.ZERO));

        newPlanner().plan(taskWithInputs());

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatWithUsage(systemCaptor.capture(), anyString());
        assertTrue(systemCaptor.getValue().contains("amount_verify"));
    }

    @Test
    void plan_genericTask_filtersFinanceTools() {
        stubToolCatalog();
        when(aiClient.chatWithUsage(anyString(), anyString())).thenReturn(new ChatReply("[]", TokenUsage.ZERO));

        AgentTask task = taskWithInputs();
        task.setTaskType(TaskType.GENERIC.name());
        newPlanner().plan(task);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatWithUsage(systemCaptor.capture(), anyString());
        assertFalse(systemCaptor.getValue().contains("amount_verify"));
    }
}
