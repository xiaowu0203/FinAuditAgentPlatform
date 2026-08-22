package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuleBasedFlowEngineTest {

    private final RuleBasedFlowEngine engine = new RuleBasedFlowEngine();

    @Test
    void planWithAttachmentsAndDepartmentBuildsFullPipeline() {
        AgentTask task = task(Map.of(
                "reimbId", 12L,
                "deptName", "研发部",
                "claimDate", "2026-08-17",
                "claimedTotal", 1000,
                "expenseType", "TRAVEL",
                "attachments", List.of(Map.of("id", 101L, "fileType", "OTHER")),
                "items", List.of(Map.of("name", "高铁", "amount", 1000))));

        List<TaskPlanStep> steps = engine.plan(task);

        assertEquals(7, steps.size());
        assertEquals("ocr_extract", steps.get(0).toolName());
        assertEquals(List.of(101L), steps.get(0).inputParams().get("attachmentIds"));
        assertEquals("budget_query", steps.get(1).toolName());
        assertEquals("amount_verify", steps.get(2).toolName());
        assertEquals("rule_check", steps.get(3).toolName());
        assertEquals("duplicate_check", steps.get(4).toolName());
        assertEquals("RISK_AUDITOR", steps.get(5).agentRole());
        assertEquals("SCHEDULER", steps.get(6).agentRole());
    }

    @Test
    void planWithoutAttachmentsOrDepartmentSkipsConditionalSteps() {
        AgentTask task = task(Map.of(
                "reimbId", 12L,
                "claimDate", "2026-08-17",
                "claimedTotal", 1000,
                "items", List.of(Map.of("name", "办公用品", "amount", 1000))));

        List<TaskPlanStep> steps = engine.plan(task);

        assertEquals(5, steps.size());
        assertEquals("amount_verify", steps.get(0).toolName());
        assertEquals("rule_check", steps.get(1).toolName());
        assertEquals("duplicate_check", steps.get(2).toolName());
        assertNotNull(steps.get(3).agentRole());
    }

    @Test
    void planConvertsLocalDateAndProjectsAmountInput() {
        AgentTask task = task(Map.of(
                "reimbId", 12L,
                "deptName", "财务部",
                "claimDate", java.time.LocalDate.of(2026, 8, 17),
                "claimedTotal", 1000,
                "items", List.of(Map.of("name", "办公用品", "amount", 1000))));

        List<TaskPlanStep> steps = engine.plan(task);

        assertEquals("2026-08-17", steps.get(0).inputParams().get("claimDate"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) steps.get(1).inputParams().get("items");
        assertEquals(1, items.size());
        assertEquals("办公用品", items.get(0).get("name"));
        assertEquals(1000, items.get(0).get("amount"));
    }

    private static AgentTask task(Map<String, Object> input) {
        AgentTask task = new AgentTask();
        task.setId(1L);
        task.setInputParams(input);
        return task;
    }
}
