package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.SelfCheckResult;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 自校验器对「真实步骤输出形态」的健壮性测试（P3.8 R5 排查用）。
 *
 * <p>背景：运行时收尾抛异常导致任务卡 RUNNING。编排器隔离测试已证明编排逻辑无误，
 * 故嫌疑落在「校验器消费真实数据时抛异常」。真实 output 是 LLM/工具回吐的任意 JSON 结构
 * （嵌套 Map/List/数字/字符串/布尔/NULL 混合），与单测里的规整 Map 差异很大。</p>
 *
 * <p>本测试用从真实流水线复制下来的 output 形态（含 LLM 长文本、数字与中文混排、
 * 深层嵌套 duplicates 数组等）驱动校验器，**任何 input 都不允许抛异常** ——
 * 自校验是收尾前置环节，它抛异常会让整条流水线静默停摆。</p>
 */
class SelfConsistencyCheckerRobustnessTest {

    private final SelfConsistencyChecker checker = new SelfConsistencyChecker();

    private static AgentTaskStep step(int no, String stepType, String tool, String role, Object output) {
        AgentTaskStep s = new AgentTaskStep();
        s.setId((long) no);
        s.setTaskId(1L);
        s.setStepNo(no);
        s.setStepName("步骤" + no);
        s.setStepType(stepType);
        s.setToolName(tool);
        s.setAgentRole(role);
        s.setStatus("SUCCESS");
        if (output instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            s.setOutput(cast);
        }
        return s;
    }

    /** 从真实流水线复制的 ocr_extract 输出形态 */
    private static Map<String, Object> realOcrOutput() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("date", "2023-06-19");
        fields.put("taxNo", "91441900MA55D6UJ41");
        fields.put("amount", 7741.75);
        fields.put("ocrDate", "2023-06-19");
        fields.put("merchant", "东莞京东旭弘贸易有限公司");
        fields.put("invoiceNum", "07632553");
        fields.put("invoiceCode", "044002311111");
        fields.put("receiptType", "vat_invoice");

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("fields", fields);
        receipt.put("status", "SUCCESS");
        receipt.put("fileType", "INVOICE");
        receipt.put("receiptType", "vat_invoice");
        receipt.put("fileRecordId", 52);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "票据识别完成，成功 1 张");
        out.put("reimbId", 53);
        out.put("receipts", List.of(receipt));
        out.put("failedCount", 0);
        out.put("successCount", 1);
        return out;
    }

    /** 真实 duplicate_check 输出（含多条命中、嵌套金额） */
    private static Map<String, Object> realDuplicateOutput() {
        List<Map<String, Object>> dups = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("title", "R3 回归-同额新单" + i);
            d.put("reimbId", 57L + i);
            d.put("reimbNo", "R202609120216514958");
            d.put("dupLevel", "LEVEL_HIGH");
            d.put("merchant", "东莞京东旭弘贸易有限公司");
            d.put("claimDate", "2026-09-01");
            d.put("invoiceNum", "07632553");
            d.put("invoiceCode", "044002311111");
            d.put("totalAmount", 9119.02);
            d.put("merchantMatched", true);
            dups.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "发现 7 条疑似重复报销，其中含发票号硬命中（同一张票已报销），需人工复核");
        out.put("dupLevel", "LEVEL_HIGH");
        out.put("suspected", true);
        out.put("suspectedHigh", true);
        out.put("duplicates", dups);
        return out;
    }

    /** 真实 LLM 风控输出（长文本 + 中文 + 数字混排） */
    private static Map<String, Object> realRiskOutput() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("riskLevel", "HIGH");
        out.put("confidence", 0.95);
        out.put("uncertain", false);
        out.put("summary", "综合前序六步结果，本单存在严重金额虚报与重复报销风险：申报合计 50102 元，"
                + "而唯一票据票面仅 7741.75 元，差额高达 42360.25 元；同时触发大额限额规则（超限 42102 元）；"
                + "发票号 07632553/044002311111 在历史单据中被 7 次硬命中。");
        out.put("riskPoints", List.of(
                "票据金额与申报明细严重不符，差额 42360.25 元",
                "大额限额超标 42102 元，超过阈值 8000 元",
                "发票号 07632553 已被多张报销单报销（疑似重复报销）"));
        return out;
    }

    /** 真实 LLM 汇总输出 */
    private static Map<String, Object> realConclusionOutput() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", "本单存在金额虚报与重复报销风险，建议驳回并请申请人补充说明");
        out.put("decision", "REJECT");
        out.put("diff", 42360.25);
        out.put("issues", List.of(
                "票据核验不通过：申报合计 50102 元大于票面合计 7741.75 元，差额 42360.25 元，明细金额虚报嫌疑",
                "明细「测试明细」金额 50102 元超过票面合计 7741.75 元",
                "重复报销检测命中 7 条疑似重复报销"));
        return out;
    }

    private static List<AgentTaskStep> realPipeline() {
        return List.of(
                step(1, "TOOL", "ocr_extract", AgentRole.DOCUMENT_PARSER.name(), realOcrOutput()),
                step(2, "TOOL", "budget_query", AgentRole.BUDGET_CALCULATOR.name(),
                        Map.of("exceedsBudget", false, "totalBudget", 100000.00, "usedAmount", 139.00)),
                step(3, "TOOL", "amount_verify", AgentRole.RULE_VALIDATOR.name(),
                        Map.of("match", true, "claimedTotal", 50102.00, "actualTotal", 50102.00)),
                step(4, "TOOL", "rule_check", AgentRole.RULE_VALIDATOR.name(),
                        Map.of("overLimit", true, "hits", List.of(Map.of(
                                "ruleCode", "amount_limit", "ruleName", "大额报销限额",
                                "ruleType", "AMOUNT_LIMIT", "message", "申报总额 50102 超过大额限额 8000",
                                "overLimit", true, "expected", 8000, "actual", 50102)))),
                step(5, "TOOL", "invoice_match", AgentRole.RULE_VALIDATOR.name(),
                        Map.of("match", false, "invoiceTotal", 7741.75, "claimTotal", 50102.00,
                                "gap", 42360.25, "flags", List.of(Map.of("code", "AMOUNT_MISMATCH",
                                        "message", "申报合计 50102 大于票面合计 7741.75")))),
                step(6, "TOOL", "duplicate_check", AgentRole.RISK_AUDITOR.name(), realDuplicateOutput()),
                step(7, "LLM", null, AgentRole.RISK_AUDITOR.name(), realRiskOutput()),
                step(8, "LLM", null, AgentRole.SCHEDULER.name(), realConclusionOutput()));
    }

    @Test
    void realPipelineOutputDoesNotThrow() {
        assertDoesNotThrow(() -> checker.check(realPipeline()),
                "真实流水线输出不得让自校验器抛异常（抛异常会让收尾静默停摆）");
    }

    @Test
    void malformedOutputShapesDoNotThrow() {
        // 畸形形态：hits 里混入非 Map、duplicates 的元素缺字段、decision 是数字、输出为 null
        // ⚠️ 用 ArrayList 而非 List.of：后者不接受 null 元素（曾在测试代码里踩到，与产品无关）
        List<Object> weirdHits = new ArrayList<>();
        weirdHits.add("not-a-map");
        weirdHits.add(123);
        weirdHits.add(Map.of("ruleType", 999));

        Map<String, Object> dupInner = new LinkedHashMap<>();
        dupInner.put("dupLevel", 123);
        dupInner.put("invoiceNum", 456);
        List<Object> weirdDups = new ArrayList<>();
        weirdDups.add(dupInner);
        weirdDups.add("not-a-map");

        List<AgentTaskStep> weird = new ArrayList<>();
        weird.add(step(1, "TOOL", "ocr_extract", null, Map.of()));
        weird.add(step(2, "TOOL", "rule_check", null, Map.of("hits", weirdHits)));
        weird.add(step(3, "TOOL", "duplicate_check", null, Map.of("duplicates", weirdDups)));
        weird.add(step(4, "LLM", null, AgentRole.RISK_AUDITOR.name(), null));   // 无输出
        weird.add(step(5, "LLM", null, AgentRole.SCHEDULER.name(), Map.of("decision", 999)));
        weird.add(step(6, "TOOL", "amount_verify", null, Map.of("match", "not-a-boolean")));

        assertDoesNotThrow(() -> checker.check(weird), "畸形形态输出不得让校验器抛异常");
    }

    @Test
    void deeplyNestedAndLargeOutputDoesNotThrow() {
        // 深层嵌套 + 大量数字（幻觉核验会遍历全部数字）
        Map<String, Object> deep = new LinkedHashMap<>();
        List<Object> level3 = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            level3.add(Map.of("num", String.valueOf(10000000 + i), "nested", Map.of("v", (double) i)));
        }
        deep.put("a", Map.of("b", Map.of("c", Map.of("d", level3))));
        deep.put("decision", "APPROVE");

        List<AgentTaskStep> steps = List.of(
                step(1, "TOOL", "ocr_extract", null, realOcrOutput()),
                step(2, "LLM", null, AgentRole.SCHEDULER.name(), deep));

        assertDoesNotThrow(() -> checker.check(steps), "深层嵌套输出不得让校验器抛异常");
    }

    @Test
    void selfCheckResultSerializesWithoutError() {
        SelfCheckResult r = checker.check(realPipeline());
        assertDoesNotThrow(() -> r.toResultMap(), "结果转 Map 不应抛异常");
        assertDoesNotThrow(() -> r.toFindings(), "结果转 findings 不应抛异常");
        assertDoesNotThrow(() -> r.toPromptHint(), "矛盾提示生成不应抛异常");
    }
}
