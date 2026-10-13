package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.SelfCheckResult;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义自校验器单测（P3.8 R5-1）。
 *
 * <p>「自主纠错」的判据基础：5 条断言必须各自可独立命中、且在数据干净时不误报。
 * 断言 ①③ 对比工具结果与<b>汇总结论</b>，② 对比规则超标与<b>风控风险等级</b>，
 * ④ 对比重复硬命中与<b>风控置信度</b>，⑤ 核验 LLM 是否引用了不存在的发票号（幻觉）。</p>
 */
class SelfConsistencyCheckerTest {

    private final SelfConsistencyChecker checker = new SelfConsistencyChecker();

    private static AgentTaskStep tool(String toolName, Map<String, Object> output) {
        AgentTaskStep s = new AgentTaskStep();
        s.setToolName(toolName);
        s.setStepType("TOOL");
        s.setOutput(output);
        return s;
    }

    private static AgentTaskStep risk(Map<String, Object> output) {
        AgentTaskStep s = new AgentTaskStep();
        s.setStepType("LLM");
        s.setAgentRole(AgentRole.RISK_AUDITOR.name());
        s.setOutput(output);
        return s;
    }

    private static AgentTaskStep conclusion(String decision) {
        AgentTaskStep s = new AgentTaskStep();
        s.setStepType("LLM");
        s.setAgentRole(AgentRole.SCHEDULER.name());
        s.setOutput(Map.of("decision", decision, "summary", "审核结论"));
        return s;
    }

    /**
     * 无角色的 LLM 步骤（GENERIC 通用任务由 TaskPlanner 规划，agentRole 为 null）。
     * <p>P3.8 R6-1：自校验的结论采集必须与角色解耦，否则通用任务的自校验会整体空转。</p>
     */
    private static AgentTaskStep rolelessLlm(int stepNo, Map<String, Object> output) {
        AgentTaskStep s = new AgentTaskStep();
        s.setStepNo(stepNo);
        s.setStepType("LLM");
        s.setAgentRole(null);
        s.setOutput(output);
        return s;
    }

    private static Map<String, Object> riskOutput(String level, String confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("riskLevel", level);
        m.put("confidence", new BigDecimal(confidence));
        m.put("uncertain", false);
        m.put("summary", "风控评估");
        m.put("riskPoints", List.of());
        return m;
    }

    // ---------------- 断言 ① 金额核验不一致 vs APPROVE ----------------

    @Test
    void assertion1AmountMismatchButApprove() {
        SelfCheckResult r = checker.check(List.of(
                tool("amount_verify", Map.of("match", false)),
                risk(riskOutput("MEDIUM", "0.8")),
                conclusion("APPROVE")));

        assertFalse(r.coherent());
        assertTrue(r.contradictions().stream().anyMatch(c -> "AMOUNT_VERIFY_VS_APPROVE".equals(c.assertion())),
                "实际=" + r.contradictions());
    }

    @Test
    void assertion1NotTriggeredWhenConclusionIsNotApprove() {
        SelfCheckResult r = checker.check(List.of(
                tool("amount_verify", Map.of("match", false)),
                risk(riskOutput("MEDIUM", "0.8")),
                conclusion("NEED_INFO")));

        assertTrue(r.contradictions().stream().noneMatch(c -> "AMOUNT_VERIFY_VS_APPROVE".equals(c.assertion())),
                "结论本就不是 APPROVE，不构成矛盾，实际=" + r.contradictions());
    }

    // ---------------- 断言 ② 规则超标 vs 风险等级 LOW ----------------

    @Test
    void assertion2OverLimitButLowRisk() {
        SelfCheckResult r = checker.check(List.of(
                tool("rule_check", Map.of("overLimit", true, "hits", List.of())),
                risk(riskOutput("LOW", "0.95")),
                conclusion("APPROVE")));

        assertTrue(r.contradictions().stream().anyMatch(c -> "RULE_OVER_LIMIT_VS_LOW_RISK".equals(c.assertion())),
                "实际=" + r.contradictions());
    }

    @Test
    void assertion2NotTriggeredWhenRiskLevelAdequate() {
        SelfCheckResult r = checker.check(List.of(
                tool("rule_check", Map.of("overLimit", true, "hits", List.of())),
                risk(riskOutput("MEDIUM", "0.8")),
                conclusion("NEED_INFO")));

        assertTrue(r.contradictions().stream().noneMatch(c -> "RULE_OVER_LIMIT_VS_LOW_RISK".equals(c.assertion())),
                "风险等级已反映超标，不构成矛盾，实际=" + r.contradictions());
    }

    // ---------------- 断言 ③ 票据核验不一致 vs APPROVE ----------------

    @Test
    void assertion3InvoiceMismatchButApprove() {
        SelfCheckResult r = checker.check(List.of(
                tool("invoice_match", Map.of("match", false, "invoiceTotal", 300, "claimTotal", 800)),
                risk(riskOutput("MEDIUM", "0.8")),
                conclusion("APPROVE")));

        assertTrue(r.contradictions().stream().anyMatch(c -> "INVOICE_MISMATCH_VS_APPROVE".equals(c.assertion())),
                "实际=" + r.contradictions());
    }

    // ---------------- 断言 ④ 重复硬命中 vs 高置信度 ----------------

    @Test
    void assertion4HardDuplicateButHighConfidence() {
        SelfCheckResult r = checker.check(List.of(
                tool("duplicate_check", Map.of("dupLevel", "LEVEL_HIGH", "suspectedHigh", true)),
                risk(riskOutput("LOW", "0.95")),
                conclusion("APPROVE")));

        assertTrue(r.contradictions().stream()
                        .anyMatch(c -> "DUPLICATE_HIGH_VS_HIGH_CONFIDENCE".equals(c.assertion())),
                "实际=" + r.contradictions());
    }

    @Test
    void assertion4NotTriggeredForMediumLevelDuplicate() {
        // 中置信仅展示不阻断，风控给高置信度不算矛盾
        SelfCheckResult r = checker.check(List.of(
                tool("duplicate_check", Map.of("dupLevel", "LEVEL_MEDIUM", "suspectedHigh", false)),
                risk(riskOutput("LOW", "0.95")),
                conclusion("APPROVE")));

        assertTrue(r.contradictions().stream()
                        .noneMatch(c -> "DUPLICATE_HIGH_VS_HIGH_CONFIDENCE".equals(c.assertion())),
                "实际=" + r.contradictions());
    }

    // ---------------- 断言 ⑤ LLM 引用不存在的发票号（幻觉） ----------------

    @Test
    void assertion5HallucinatedInvoiceNum() {
        // 真实票号是 07632553（8 位），LLM 在 summary 里引用了同长度的 99999999 —— 该号不存在
        Map<String, Object> riskWithHallucination = new LinkedHashMap<>(riskOutput("MEDIUM", "0.8"));
        riskWithHallucination.put("summary", "该发票 99999999 与明细不符");

        SelfCheckResult r = checker.check(List.of(
                tool("ocr_extract", Map.of("receipts", List.of(
                        Map.of("fields", Map.of("invoiceNum", "07632553", "invoiceCode", "044002311111"))))),
                risk(riskWithHallucination),
                conclusion("NEED_INFO")));

        assertTrue(r.hasHallucination(), "应命中幻觉，实际=" + r.contradictions());
        assertTrue(r.contradictions().stream().anyMatch(c -> "HALLUCINATED_INVOICE_NUM".equals(c.assertion())),
                "实际=" + r.contradictions());
    }

    @Test
    void assertion5NotTriggeredWhenMentionedInvoiceIsReal() {
        Map<String, Object> riskWithRealInvoice = new LinkedHashMap<>(riskOutput("MEDIUM", "0.8"));
        riskWithRealInvoice.put("summary", "该发票 07632553 金额与明细一致");

        SelfCheckResult r = checker.check(List.of(
                tool("ocr_extract", Map.of("receipts", List.of(
                        Map.of("fields", Map.of("invoiceNum", "07632553", "invoiceCode", "044002311111"))))),
                risk(riskWithRealInvoice),
                conclusion("APPROVE")));

        assertFalse(r.hasHallucination(), "引用的是真实票号，不应判幻觉，实际=" + r.contradictions());
    }

    @Test
    void assertion5IgnoresNumbersOfDifferentLength() {
        // 金额/日期这类数字与票号长度不同，不得误判为幻觉票号
        Map<String, Object> riskWithAmount = new LinkedHashMap<>(riskOutput("MEDIUM", "0.8"));
        riskWithAmount.put("summary", "申报金额 800 元，日期 20260901 有效");

        SelfCheckResult r = checker.check(List.of(
                tool("ocr_extract", Map.of("receipts", List.of(
                        Map.of("fields", Map.of("invoiceNum", "07632553"))))),
                risk(riskWithAmount),
                conclusion("APPROVE")));

        assertFalse(r.hasHallucination(), "不同长度的数字不应判幻觉，实际=" + r.contradictions());
    }

    // ---------------- 通过路径与边界 ----------------

    @Test
    void cleanPipelinePassesAllAssertions() {
        SelfCheckResult r = checker.check(List.of(
                tool("amount_verify", Map.of("match", true)),
                tool("rule_check", Map.of("overLimit", false, "hits", List.of())),
                tool("invoice_match", Map.of("match", true)),
                tool("duplicate_check", Map.of("dupLevel", "NONE", "suspectedHigh", false)),
                risk(riskOutput("LOW", "0.9")),
                conclusion("APPROVE")));

        assertTrue(r.coherent(), "干净流水线应通过，实际=" + r.contradictions());
        assertTrue(r.contradictions().isEmpty());
        assertEquals(SelfConsistencyChecker.assertionCount(), r.checkedCount(), "应报告校验的断言条数");
    }

    @Test
    void emptyStepsPassWithoutMisjudgement() {
        assertTrue(checker.check(List.of()).coherent());
        assertTrue(checker.check(null).coherent());
    }

    @Test
    void contradictoryFindingsAreStructured() {
        SelfCheckResult r = checker.check(List.of(
                tool("amount_verify", Map.of("match", false)),
                risk(riskOutput("LOW", "0.95")),
                conclusion("APPROVE")));

        // 转成 R4 的结构化问题项后级别归 RISK_HIT，可直接并入工单 findings
        var findings = r.toFindings();
        assertFalse(findings.isEmpty());
        assertEquals(SelfCheckResult.TYPE_CONTRADICTION, r.contradictions().get(0).type());
        assertTrue(findings.get(0).code().startsWith("SELF_CHECK_"));
        assertTrue(r.toPromptHint().contains("自校验发现矛盾"), "应生成给 LLM 的矛盾提示");
    }

    // ---------------- R6-1：结论采集与角色解耦（GENERIC 通用任务） ----------------

    /**
     * 无 SCHEDULER 角色时，结论取 stepNo 最大的 LLM 步骤。
     * <p>GENERIC 任务的 LLM 步骤由 TaskPlanner 规划、agentRole 为 null；不做这层退化，
     * 断言 ①③ 会因拿不到结论而恒不命中（自校验形同不存在）。</p>
     */
    @Test
    void conclusionFallsBackToLastLlmStepWhenNoSchedulerRole() {
        // 金额核验不一致 + 结论 APPROVE（无角色）→ 断言 ① 命中
        SelfCheckResult r = checker.check(List.of(
                tool("amount_verify", Map.of("match", false)),
                rolelessLlm(2, Map.of("decision", "APPROVE", "summary", "分析结论"))));

        assertFalse(r.coherent(), "无角色任务的结论也应参与断言 ①，实际=" + r.contradictions());
        assertEquals("AMOUNT_VERIFY_VS_APPROVE", r.contradictions().get(0).assertion());
    }

    /** 多个无角色 LLM 步骤时取 stepNo 最大者（不依赖入参顺序） */
    @Test
    void conclusionPrefersHighestStepNoAmongRolelessLlmSteps() {
        SelfCheckResult r = checker.check(List.of(
                rolelessLlm(3, Map.of("decision", "APPROVE")),
                tool("amount_verify", Map.of("match", false)),
                // 顺序刻意打乱：stepNo=5 才是最后的汇总结论
                rolelessLlm(5, Map.of("decision", "APPROVE")),
                rolelessLlm(4, Map.of("decision", "APPROVE"))));

        assertFalse(r.coherent());
        assertEquals("AMOUNT_VERIFY_VS_APPROVE", r.contradictions().get(0).assertion());
    }

    /** 有 SCHEDULER 角色时仍优先取它（报销流水线行为不因 R6-1 改动而漂移） */
    @Test
    void schedulerRoleStillTakesPrecedenceOverRolelessLlm() {
        SelfCheckResult r = checker.check(List.of(
                tool("amount_verify", Map.of("match", false)),
                rolelessLlm(9, Map.of("decision", "REJECT")),
                conclusion("APPROVE")));

        // SCHEDULER 的 APPROVE 才是结论 → 断言 ① 命中（若误取 REJECT 则不会命中）
        assertFalse(r.coherent(), "应以 SCHEDULER 角色步骤为结论，实际=" + r.contradictions());
        assertEquals("AMOUNT_VERIFY_VS_APPROVE", r.contradictions().get(0).assertion());
    }

    /** 无角色 LLM 步骤的自由文本同样纳入幻觉核验 */
    @Test
    void rolelessLlmFreeTextIsScannedForHallucination() {
        SelfCheckResult r = checker.check(List.of(
                tool("ocr_extract", Map.of("receipts", List.of(Map.of("fields", Map.of("invoiceNum", "07632553"))))),
                rolelessLlm(2, Map.of("summary", "发票 12345678 与票面不一致"))));

        assertTrue(r.hasHallucination(), "无角色 LLM 引用的不存在票号应判为幻觉，实际=" + r.contradictions());
    }
}
