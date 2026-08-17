package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.FlowDecision;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewFlowDeciderTest {

    private final ReviewFlowDecider decider = new ReviewFlowDecider();

    @Test
    void cleanApprovedPipelineAutoPasses() {
        FlowDecision decision = decider.decide(List.of(
                step("amount_verify", Map.of("match", true)),
                step("budget_query", Map.of("exceedsBudget", false)),
                step("rule_check", Map.of("overLimit", false, "hits", List.of())),
                step("duplicate_check", Map.of("suspected", false)),
                step(null, Map.of("confidence", 0.95, "uncertain", false), "LLM", AgentRole.RISK_AUDITOR.name()),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.AUTO_PASS, decision.flowBranch());
        assertTrue(decision.reviewReasons().isEmpty());
    }

    @Test
    void amountMismatchAndRuleHitNeedReview() {
        FlowDecision decision = decider.decide(List.of(
                step("amount_verify", Map.of("match", false)),
                step("rule_check", Map.of("overLimit", true,
                        "hits", List.of(Map.of("ruleType", "AMOUNT_LIMIT", "ruleName", "大额限额")))),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("RISK_HIT:")));
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("OVER_LIMIT:")));
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("RULE_FAIL:")));
    }

    @Test
    void lowRiskConfidenceAndNonApproveNeedReview() {
        FlowDecision decision = decider.decide(List.of(
                step(null, Map.of("confidence", 0.6, "uncertain", false), "LLM", AgentRole.RISK_AUDITOR.name()),
                step(null, Map.of("decision", "NEED_INFO"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        assertTrue(decision.reviewReasons().contains("RISK_HIT:风控置信度低于 0.7"));
        assertTrue(decision.reviewReasons().contains("LLM_DECISION:NEED_INFO"));
    }

    private static AgentTaskStep step(String tool, Map<String, Object> output) {
        return step(tool, output, "TOOL", null);
    }

    private static AgentTaskStep step(String tool, Map<String, Object> output, String type, String role) {
        AgentTaskStep step = new AgentTaskStep();
        step.setToolName(tool);
        step.setStepType(type);
        step.setAgentRole(role);
        step.setOutput(output);
        return step;
    }
}
