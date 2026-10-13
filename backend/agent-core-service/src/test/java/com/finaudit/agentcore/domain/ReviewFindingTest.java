package com.finaudit.agentcore.domain;

import com.finaudit.agentcore.service.ReviewFlowDecider;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化审核问题项单测（P3.8 R4-1 / R4-2）。
 *
 * <p>业务目标「驳回重提引导」的直接支撑点：提交人必须能看到
 * <b>该改哪一行、标准是多少、实际是多少、差多少</b>，而不是只看到一句「规则校验超标」。
 * 本测试锁定这些结构化字段确实被填出，且不依赖从文本反解。</p>
 */
class ReviewFindingTest {

    private final ReviewFlowDecider decider = new ReviewFlowDecider();

    private static AgentTaskStep toolStep(String tool, Map<String, Object> output) {
        AgentTaskStep s = new AgentTaskStep();
        s.setToolName(tool);
        s.setStepType("TOOL");
        s.setOutput(output);
        return s;
    }

    private static AgentTaskStep llmStep(String decision) {
        AgentTaskStep s = new AgentTaskStep();
        s.setStepType("LLM");
        s.setAgentRole(AgentRole.SCHEDULER.name());
        s.setOutput(Map.of("decision", decision));
        return s;
    }

    // ---------------- 结构本身 ----------------

    @Test
    void ofItemComputesGap() {
        ReviewFinding f = ReviewFinding.ofItem("TRAVEL_STANDARD", ReviewFinding.LEVEL_RULE_FAIL,
                2, "住宿费", new BigDecimal("500.00"), new BigDecimal("680.00"), "请调整");

        assertEquals(0, new BigDecimal("180.00").compareTo(f.gap()), "差额应为 680-500=180");
        assertEquals(2, f.itemIndex());
        assertEquals("住宿费", f.itemName());
    }

    @Test
    void ofDocumentHasNoItemLocatorOrGap() {
        ReviewFinding f = ReviewFinding.ofDocument("BUDGET_EXCEEDED", ReviewFinding.LEVEL_RULE_FAIL, "预算超支");

        assertNull(f.itemIndex());
        assertNull(f.itemName());
        assertNull(f.expected());
        assertNull(f.gap());
    }

    @Test
    void gapIsNullWhenEitherSideMissing() {
        // 只有单侧数值时不强造差额——避免前端展示「差额 null 元」
        ReviewFinding f = new ReviewFinding("X", ReviewFinding.LEVEL_RULE_FAIL, 1, "明细",
                null, new BigDecimal("100"), null, "建议");
        assertNull(f.gap());
    }

    @Test
    void toReasonCarriesItemLocatorAndGap() {
        ReviewFinding f = ReviewFinding.ofItem("TRAVEL_STANDARD", ReviewFinding.LEVEL_RULE_FAIL,
                0, "住宿费", new BigDecimal("500"), new BigDecimal("680"), "请调整");

        String reason = f.toReason();
        assertTrue(reason.startsWith("RULE_FAIL:"), "实际=" + reason);
        // 行号按人读习惯 1 起
        assertTrue(reason.contains("住宿费"), "实际=" + reason);
        assertTrue(reason.contains("第1行"), "实际=" + reason);
        assertTrue(reason.contains("680"), "实际=" + reason);
        assertTrue(reason.contains("500"), "实际=" + reason);
        assertTrue(reason.contains("180"), "超差额应出现在原因串里，实际=" + reason);
    }

    @Test
    void toReasonKeepsLlmDecisionPrefix() {
        // LLM_DECISION 是 FlowDecision 文档化的既有前缀，不得被结构化改造改成 RISK_HIT
        ReviewFinding f = new ReviewFinding(ReviewFinding.CODE_LLM_DECISION, ReviewFinding.LEVEL_RISK_HIT,
                null, null, null, null, null, "审核结论为 NEED_INFO，需人工确认");

        assertTrue(f.toReason().startsWith("LLM_DECISION:"), "实际=" + f.toReason());
    }

    // ---------------- 判定器产出结构化问题项（R4-2） ----------------

    @Test
    void ruleCheckHitProducesItemLevelFindingWithNumericFields() {
        // rule_check 输出带结构化数值（R4 起 RuleHitVO 直接给出 expected/actual/itemIndex）
        FlowDecision decision = decider.decide(List.of(
                toolStep("rule_check", Map.of("overLimit", true, "hits", List.of(Map.of(
                        "ruleType", "TRAVEL_STANDARD", "ruleName", "差旅标准",
                        "itemIndex", 1, "itemName", "住宿费",
                        "expected", 500, "actual", 680)))),
                llmStep("APPROVE")));

        assertEquals(FlowDecision.NEED_REVIEW, decision.flowBranch());
        ReviewFinding f = decision.findings().stream()
                .filter(x -> "TRAVEL_STANDARD".equals(x.code())).findFirst().orElseThrow();
        assertEquals(ReviewFinding.LEVEL_RULE_FAIL, f.level());
        assertEquals(1, f.itemIndex(), "必须定位到明细行");
        assertEquals("住宿费", f.itemName());
        assertEquals(0, new BigDecimal("500").compareTo(f.expected()), "标准值应结构化给出");
        assertEquals(0, new BigDecimal("680").compareTo(f.actual()), "实际值应结构化给出");
        assertEquals(0, new BigDecimal("180").compareTo(f.gap()), "差额应结构化给出");
    }

    @Test
    void amountLimitHitProducesOverLimitLevelWithDocumentAmounts() {
        FlowDecision decision = decider.decide(List.of(
                toolStep("rule_check", Map.of("overLimit", true, "hits", List.of(Map.of(
                        "ruleType", "AMOUNT_LIMIT", "ruleName", "大额限额",
                        "expected", 500, "actual", 553)))),
                llmStep("APPROVE")));

        // OVER_LIMIT 决定工单 triggerType，故必须存在该级别的 finding
        ReviewFinding over = decision.findings().stream()
                .filter(x -> ReviewFinding.LEVEL_OVER_LIMIT.equals(x.level())).findFirst().orElseThrow();
        assertEquals("AMOUNT_LIMIT", over.code());
        assertNull(over.itemIndex(), "大额限额是单据级问题，不应有明细行定位");
        assertEquals(0, new BigDecimal("53").compareTo(over.gap()));
    }

    @Test
    void duplicateHardHitFindingCarriesInvoiceNumber() {
        FlowDecision decision = decider.decide(List.of(
                toolStep("duplicate_check", Map.of("dupLevel", "LEVEL_HIGH", "suspectedHigh", true,
                        "duplicates", List.of(Map.of("dupLevel", "LEVEL_HIGH",
                                "invoiceCode", "044002311111", "invoiceNum", "07632553",
                                "reimbNo", "R202601010000000001")))),
                llmStep("APPROVE")));

        ReviewFinding f = decision.findings().stream()
                .filter(x -> "DUPLICATE_INVOICE".equals(x.code())).findFirst().orElseThrow();
        assertEquals(ReviewFinding.LEVEL_RISK_HIT, f.level());
        // 命中说明要能直接告诉人工「和哪张单撞了」
        assertTrue(f.suggestion().contains("07632553"), "实际=" + f.suggestion());
        assertTrue(f.suggestion().contains("R202601010000000001"), "实际=" + f.suggestion());
    }

    @Test
    void invoiceMatchFindingCarriesTotals() {
        FlowDecision decision = decider.decide(List.of(
                toolStep("invoice_match", Map.of("match", false,
                        "invoiceTotal", 7741.75, "claimTotal", 50000,
                        "flags", List.of(Map.of("code", "AMOUNT_MISMATCH", "message", "申报超票面")))),
                llmStep("APPROVE")));

        ReviewFinding f = decision.findings().stream()
                .filter(x -> "AMOUNT_MISMATCH".equals(x.code())).findFirst().orElseThrow();
        assertEquals(ReviewFinding.LEVEL_RULE_FAIL, f.level());
        assertNotNull(f.expected(), "票面合计应作为标准值");
        assertNotNull(f.actual(), "申报合计应作为实际值");
        assertEquals(0, new BigDecimal("42258.25").compareTo(f.gap()), "差额应为申报-票面");
    }

    @Test
    void reasonsAreDerivedFromFindingsConsistently() {
        // reasons 与 findings 必须一一对应（reasons 是派生视图，不是独立数据源）
        FlowDecision decision = decider.decide(List.of(
                toolStep("rule_check", Map.of("overLimit", true, "hits", List.of(Map.of(
                        "ruleType", "SUBSIDY_LIMIT", "ruleName", "补贴限额",
                        "itemIndex", 0, "itemName", "市内交通补贴",
                        "expected", 200, "actual", 260)))),
                llmStep("APPROVE")));

        assertEquals(decision.findings().size(), decision.reviewReasons().size(),
                "reasons 应由 findings 逐条派生");
        for (int i = 0; i < decision.findings().size(); i++) {
            assertEquals(decision.findings().get(i).toReason(), decision.reviewReasons().get(i));
        }
    }

    @Test
    void autoPassHasNoFindings() {
        FlowDecision decision = decider.decide(List.of(
                toolStep("amount_verify", Map.of("match", true)),
                llmStep("APPROVE")));

        assertEquals(FlowDecision.AUTO_PASS, decision.flowBranch());
        assertTrue(decision.findings().isEmpty());
        assertTrue(!decision.hasFindings());
    }
}
