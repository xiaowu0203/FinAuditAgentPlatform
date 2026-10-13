package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.FlowDecision;
import com.finaudit.agentcore.domain.ReviewFinding;
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
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("RISK_HIT:")), "实际=" + decision.reviewReasons());
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("OVER_LIMIT:")), "实际=" + decision.reviewReasons());
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("RULE_FAIL:")), "实际=" + decision.reviewReasons());
    }

    /** 规则校验只有全局超标标记、无具体命中项时，补一条单据级 RULE_FAIL 兜底 */
    @Test
    void ruleCheckOverLimitWithoutHitsFallsBackToRuleFail() {
        FlowDecision decision = decider.decide(List.of(
                step("rule_check", Map.of("overLimit", true, "hits", List.of())),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("RULE_FAIL:")), "实际=" + decision.reviewReasons());
    }

    @Test
    void lowRiskConfidenceAndNonApproveNeedReview() {
        FlowDecision decision = decider.decide(List.of(
                step(null, Map.of("confidence", 0.6, "uncertain", false), "LLM", AgentRole.RISK_AUDITOR.name()),
                step(null, Map.of("decision", "NEED_INFO"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        // P3.8 R4：文案由 findings 派生，断言改为「前缀 + 关键语义」而非逐字比对
        assertTrue(decision.reviewReasons().stream()
                        .anyMatch(v -> v.startsWith("RISK_HIT:") && v.contains("置信度低于 0.7")),
                "实际=" + decision.reviewReasons());
        assertTrue(decision.reviewReasons().stream()
                        .anyMatch(v -> v.startsWith("LLM_DECISION:") && v.contains("NEED_INFO")),
                "实际=" + decision.reviewReasons());
        // 结构化问题项：无明细定位、级别为 RISK_HIT
        assertTrue(decision.findings().stream()
                        .anyMatch(f -> "RISK_CONFIDENCE_LOW".equals(f.code())
                                && ReviewFinding.LEVEL_RISK_HIT.equals(f.level())
                                && f.itemIndex() == null),
                "实际=" + decision.findings());
    }

    @Test
    void promptInjectionSynthesizedOutputForcesReview() {
        // P3c：Prompt 注入命中时，executeLlmStep 不调 LLM 而产出 uncertain=true/confidence=0 的
        // RiskAssessment → ReviewFlowDecider 须判 NEED_REVIEW（强制人工，命中不直接放行）。
        FlowDecision decision = decider.decide(List.of(
                step(null, Map.of("riskLevel", "HIGH", "confidence", 0, "uncertain", true,
                        "summary", "检测到疑似Prompt注入，已强制人工复核",
                        "riskPoints", List.of("[LLM_STEP] 命中注入规则")), "LLM", AgentRole.RISK_AUDITOR.name()),
                step(null, Map.of("decision", "NEED_INFO"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        assertTrue(decision.reviewReasons().stream()
                        .anyMatch(v -> v.startsWith("RISK_HIT:") && v.contains("存疑")),
                "实际=" + decision.reviewReasons());
        // 汇总步骤为 NEED_INFO（非 APPROVE），同步命中
        assertTrue(decision.reviewReasons().stream()
                        .anyMatch(v -> v.startsWith("LLM_DECISION:") && v.contains("NEED_INFO")),
                "实际=" + decision.reviewReasons());
        assertTrue(decision.findings().stream().anyMatch(f -> "RISK_UNCERTAIN".equals(f.code())));
    }

    // ---------- P3.8 R3：重复报销分级 + 票据核验 ----------

    @Test
    void mediumLevelDuplicateDoesNotTriggerRiskHit() {
        // B-4 根治点：中置信（金额+商户近似）只作展示，不得触发风控命中。
        // 这正是 R1 联调中那张 100 元小额单被误判为重复的路径。
        FlowDecision decision = decider.decide(List.of(
                step("duplicate_check", Map.of("dupLevel", "LEVEL_MEDIUM",
                        "suspectedHigh", false, "suspected", true)),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.AUTO_PASS, decision.flowBranch(),
                "仅中置信疑似重复不得阻断自动通过，实际原因=" + decision.reviewReasons());
    }

    @Test
    void highLevelDuplicateTriggersRiskHit() {
        // 一级：发票号硬命中（同一张票已报销）→ 必须触发风控命中
        FlowDecision decision = decider.decide(List.of(
                step("duplicate_check", Map.of("dupLevel", "LEVEL_HIGH",
                        "suspectedHigh", true, "suspected", true)),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.contains("发票号硬命中")),
                "实际原因=" + decision.reviewReasons());
    }

    @Test
    void legacyDuplicateOutputWithoutDupLevelStillTriggers() {
        // 兼容：老输出无 suspectedHigh 字段时回落到 suspected，行为不突变
        FlowDecision decision = decider.decide(List.of(
                step("duplicate_check", Map.of("suspected", true)),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        assertTrue(decision.reviewReasons().stream().anyMatch(v -> v.startsWith("RISK_HIT:")),
                "实际=" + decision.reviewReasons());
        assertTrue(decision.findings().stream().anyMatch(f -> "DUPLICATE_INVOICE".equals(f.code())));
    }

    @Test
    void invoiceMismatchProducesRuleFailWithFlagCodes() {
        FlowDecision decision = decider.decide(List.of(
                step("invoice_match", Map.of("match", false, "flags", List.of(
                        Map.of("code", "AMOUNT_MISMATCH", "message", "申报合计大于票面合计"),
                        Map.of("code", "ITEM_EXCEEDS_INVOICE", "message", "单笔超过票面合计")))),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        // P3.8 R4：异常编码改为落在 findings.code（结构化后不必再塞进文本才能定位）
        assertTrue(decision.findings().stream().anyMatch(f -> "AMOUNT_MISMATCH".equals(f.code())),
                "实际=" + decision.findings());
        assertTrue(decision.findings().stream().anyMatch(f -> "ITEM_EXCEEDS_INVOICE".equals(f.code())),
                "实际=" + decision.findings());
        assertTrue(decision.reviewReasons().stream().allMatch(v -> v.startsWith("RULE_FAIL:")),
                "票据不一致应统一归 RULE_FAIL，实际=" + decision.reviewReasons());
    }

    @Test
    void consistentInvoiceMatchDoesNotBlock() {
        FlowDecision decision = decider.decide(List.of(
                step("invoice_match", Map.of("match", true, "flags", List.of())),
                step(null, Map.of("decision", "APPROVE"), "LLM", AgentRole.SCHEDULER.name())));

        assertEquals(FlowDecision.AUTO_PASS, decision.flowBranch(),
                "票据一致不得阻断，实际原因=" + decision.reviewReasons());
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
