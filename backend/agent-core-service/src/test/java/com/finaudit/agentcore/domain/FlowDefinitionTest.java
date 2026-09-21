package com.finaudit.agentcore.domain;

import com.finaudit.agentcore.enums.StepType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明式流水线定义单测（P3.8 R6-5）。
 *
 * <p>把流水线结构（顺序 / 条件 / 入参投影）钉在断言里：改动声明时若有步骤顺序或条件被误改，
 * 这里会先红——比"跑一遍端到端再发现"便宜得多。</p>
 */
class FlowDefinitionTest {

    private final FlowDefinition def = FlowDefinition.reimbursementDefault();

    @Test
    void declarationHasEightStepsInDeclaredOrder() {
        List<FlowStepDefinition> steps = def.steps();
        assertEquals(8, steps.size(), "报销默认流水线应有 8 条声明");
        // 声明序号严格递增，且物化顺序与之一致
        for (int i = 0; i < steps.size(); i++) {
            assertEquals(i + 1, steps.get(i).order(), "声明序号应从 1 递增");
        }
        assertEquals("票据解析", steps.get(0).stepName());
        assertEquals("审核结论汇总", steps.get(7).stepName());
        assertEquals(StepType.LLM, steps.get(6).stepType());
        assertEquals(StepType.LLM, steps.get(7).stepType());
    }

    @Test
    void ticketVerificationSitsRightAfterRuleCheck() {
        // 设计约束：invoice_match 必须紧跟 rule_check（此时限额已判完，正好校验明细是否虚报）
        Map<String, Object> in = Map.of("reimbId", 1L, "deptName", "研发部", "claimDate", "2026-09-01",
                "claimedTotal", 1000, "expenseType", "OFFICE",
                "attachments", List.of(Map.of("id", 101L)), "items", List.of(Map.of("name", "x", "amount", 1000)));

        List<TaskPlanStep> plan = def.materialize(in);

        assertEquals(8, plan.size());
        assertEquals("rule_check", plan.get(3).toolName());
        assertEquals("invoice_match", plan.get(4).toolName());
        assertEquals("duplicate_check", plan.get(5).toolName());
        assertEquals(null, plan.get(6).toolName(), "LLM 步骤无工具编码");
    }

    @Test
    void conditionalStepsSkippedWhenInputsMissing() {
        Map<String, Object> in = Map.of("reimbId", 1L, "claimDate", "2026-09-01",
                "claimedTotal", 1000, "items", List.of(Map.of("name", "x", "amount", 1000)));

        List<TaskPlanStep> plan = def.materialize(in);

        // 无附件 ⇒ 无 ocr_extract / invoice_match；无 deptName ⇒ 无 budget_query
        assertEquals(5, plan.size());
        assertEquals("amount_verify", plan.get(0).toolName());
        assertEquals("rule_check", plan.get(1).toolName());
        assertEquals("duplicate_check", plan.get(2).toolName());
        assertTrue(plan.stream().noneMatch(s -> "ocr_extract".equals(s.toolName())));
        assertTrue(plan.stream().noneMatch(s -> "invoice_match".equals(s.toolName())));
        assertTrue(plan.stream().noneMatch(s -> "budget_query".equals(s.toolName())));
    }

    @Test
    void llmStepProjectionIsNullAndToolStepProjectionIsNot() {
        List<FlowStepDefinition> steps = def.steps();
        TaskPlanStep llm = steps.get(7).materialize(Map.of());
        assertNotNull(llm);
        assertNull(llm.inputParams(), "LLM 步骤不携带工具入参");

        TaskPlanStep budget = steps.get(1).materialize(Map.of("deptName", "研发部", "claimedTotal", 10));
        assertEquals("研发部", budget.inputParams().get("deptName"));
        assertEquals(10, budget.inputParams().get("amount"), "预算步骤的 amount 取自 claimedTotal");
    }

    @Test
    void conditionIsEvaluatedAgainstInputNotMutation() {
        // 条件步骤的判据只读入参：同一份入参重复物化结果一致（声明无副作用）
        Map<String, Object> in = Map.of("reimbId", 1L, "deptName", "研发部", "claimDate", "2026-09-01",
                "claimedTotal", 1000, "attachments", List.of(Map.of("id", 5L)),
                "items", List.of(Map.of("name", "x", "amount", 1000)));
        assertEquals(def.materialize(in).size(), def.materialize(in).size());
    }
}
