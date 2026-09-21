package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.SelfCheckResult;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义自校验器（P3.8 R5，A-1 自主纠错核心）。
 *
 * <p>在流水线收尾判定 AUTO_PASS 之前，对<b>各步骤结果与本轮 LLM 结论</b>做确定性交叉一致性校验。
 * 命中矛盾即返回不通过，由 {@code AgentOrchestrator} 驱动重跑风控语义步骤（上限 1 次），
 * 超限则转人工复核并附结构化问题项。</p>
 *
 * <p><b>为什么放在收尾闸口而不是流水线中间</b>：断言 ①③ 要对比「汇总结论」，
 * 而汇总结论由最后一个 LLM 步骤产出。若把校验步骤插在汇总之前，这两条断言拿不到结论、
 * 只能空转。故自校验主逻辑在收尾时执行——此时风控评估与汇总结论都已落库，
 * 5 条断言都有真实数据可比。</p>
 *
 * <p><b>断言集</b>（前四条消费 R2/R3 落库的真实业务数据，非空转）：</p>
 * <ol>
 *   <li>金额核验不一致，但汇总结论 APPROVE</li>
 *   <li>规则校验超标（含大额限额），但风险等级 LOW</li>
 *   <li>票据与明细交叉核验不一致，但汇总结论 APPROVE</li>
 *   <li>重复报销硬命中（同一张票已报销），但风控置信度 ≥ 0.9</li>
 *   <li>LLM 输出引用了前序步骤中不存在的发票号码（幻觉）</li>
 * </ol>
 */
@Component
public class SelfConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(SelfConsistencyChecker.class);

    /** 断言 ④ 的置信度阈值：硬命中重复却给出高置信度，说明风险步骤没看到/忽略了该事实 */
    private static final BigDecimal SUSPICIOUS_CONFIDENCE = new BigDecimal("0.9");

    /** 从 LLM 自由文本中提取疑似发票号码（连续 8~20 位数字，含它们常见的纯数字形态） */
    private static final Pattern INVOICE_NUM_PATTERN = Pattern.compile("\\b(\\d{8,20})\\b");

    /** 可用于幻觉核验的字段名（LLM 若引用这些字段，其值必须在前序步骤里真实存在） */
    private static final Set<String> VERIFIABLE_KEYS = Set.of("invoiceNum", "invoiceCode");

    /** 断言总数（用于 SelfCheckResult.checkedCount 的可观测性口径） */
    private static final int ASSERTION_COUNT = 5;

    /** LLM 步骤类型标识（结论与自由文本的采集口径） */
    private static final String STEP_TYPE_LLM = "LLM";

    /**
     * 执行语义自校验。
     *
     * @param steps 任务全部步骤（含各自 output）
     * @return 校验结果；无 LLM 输出时视为通过（无可校验结论，不应误判）
     */
    public SelfCheckResult check(List<AgentTaskStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return SelfCheckResult.pass(0);
        }
        Map<String, Map<String, Object>> toolOutputs = new LinkedHashMap<>();
        Map<String, Object> riskOutput = null;
        String llmFreeText = "";
        // 汇总结论步骤的选取（P3.8 R6-1 起与角色解耦）：
        // ① 优先 SCHEDULER 角色（报销流水线的「审核结论汇总」步骤）；
        // ② 若不存在（GENERIC 通用任务由 TaskPlanner 规划，LLM 步骤 agentRole 为 null），
        //    退化为 stepNo 最大的 LLM 步骤——即流水线的最后一个 LLM 步骤。
        // 不做这层退化，通用任务的自校验会因拿不到结论而整体空转（autoPassLike 恒真、自由文本为空）。
        AgentTaskStep schedulerStep = null;
        AgentTaskStep lastLlmStep = null;

        for (AgentTaskStep s : steps) {
            Map<String, Object> out = s.getOutput();
            if (out == null) {
                continue;
            }
            String tool = s.getToolName();
            if (tool != null && !tool.isBlank()) {
                toolOutputs.put(tool, out);
            }
            if (AgentRole.RISK_AUDITOR.name().equals(s.getAgentRole())) {
                riskOutput = out;
            }
            if (!STEP_TYPE_LLM.equalsIgnoreCase(s.getStepType())) {
                continue;
            }
            // 所有 LLM 步骤的自由文本都纳入幻觉核验（summary / riskPoints / 结论都是 LLM 自己写的）
            llmFreeText = llmFreeText + " " + flatten(out);
            if (AgentRole.SCHEDULER.name().equals(s.getAgentRole())) {
                schedulerStep = s;
            }
            if (lastLlmStep == null || stepNoOf(s) >= stepNoOf(lastLlmStep)) {
                lastLlmStep = s;
            }
        }
        AgentTaskStep conclusionStep = schedulerStep != null ? schedulerStep : lastLlmStep;
        String conclusion = null;
        if (conclusionStep != null && conclusionStep.getOutput() != null) {
            Object d = conclusionStep.getOutput().get("decision");
            conclusion = d == null ? null : d.toString();
        }

        List<SelfCheckResult.Contradiction> hits = new ArrayList<>();
        boolean approve = conclusion != null && "APPROVE".equalsIgnoreCase(conclusion);
        boolean autoPassLike = conclusion == null || approve;

        // ---------- 断言 ① 金额核验不一致但结论 APPROVE ----------
        Map<String, Object> amountVerify = toolOutputs.get("amount_verify");
        if (amountVerify != null && Boolean.FALSE.equals(amountVerify.get("match")) && autoPassLike) {
            hits.add(new SelfCheckResult.Contradiction("AMOUNT_VERIFY_VS_APPROVE",
                    SelfCheckResult.TYPE_CONTRADICTION,
                    "金额核验不一致（match=false），但汇总结论为 APPROVE，结论与工具结果冲突"));
        }

        // ---------- 断言 ② 规则超标但风险等级 LOW ----------
        Map<String, Object> ruleCheck = toolOutputs.get("rule_check");
        String riskLevel = riskOutput == null ? null : str(riskOutput.get("riskLevel"));
        if (ruleCheck != null && Boolean.TRUE.equals(ruleCheck.get("overLimit"))
                && "LOW".equalsIgnoreCase(riskLevel)) {
            hits.add(new SelfCheckResult.Contradiction("RULE_OVER_LIMIT_VS_LOW_RISK",
                    SelfCheckResult.TYPE_CONTRADICTION,
                    "规则校验判定超标（overLimit=true），但风控风险等级为 LOW，风险评估遗漏了超标事实"));
        }

        // ---------- 断言 ③ 票据交叉核验不一致但结论 APPROVE ----------
        Map<String, Object> invoiceMatch = toolOutputs.get("invoice_match");
        if (invoiceMatch != null && Boolean.FALSE.equals(invoiceMatch.get("match")) && autoPassLike) {
            hits.add(new SelfCheckResult.Contradiction("INVOICE_MISMATCH_VS_APPROVE",
                    SelfCheckResult.TYPE_CONTRADICTION,
                    "票据与明细交叉核验不一致（match=false），但汇总结论为 APPROVE，结论与票据事实冲突"));
        }

        // ---------- 断言 ④ 重复报销硬命中但风控高置信度 ----------
        Map<String, Object> duplicate = toolOutputs.get("duplicate_check");
        boolean hardDuplicate = duplicate != null && (Boolean.TRUE.equals(duplicate.get("suspectedHigh"))
                || "LEVEL_HIGH".equals(str(duplicate.get("dupLevel"))));
        BigDecimal confidence = riskOutput == null ? null : decimal(riskOutput.get("confidence"));
        if (hardDuplicate && confidence != null && confidence.compareTo(SUSPICIOUS_CONFIDENCE) >= 0) {
            hits.add(new SelfCheckResult.Contradiction("DUPLICATE_HIGH_VS_HIGH_CONFIDENCE",
                    SelfCheckResult.TYPE_CONTRADICTION,
                    "发票号硬命中重复报销，但风控置信度高达 " + confidence.stripTrailingZeros().toPlainString()
                            + "（≥" + SUSPICIOUS_CONFIDENCE.toPlainString() + "），风险步骤可能忽略了重复事实"));
        }

        // ---------- 断言 ⑤ LLM 引用了前序步骤中不存在的发票号码（幻觉） ----------
        Set<String> realInvoiceNums = collectInvoiceNums(toolOutputs.values());
        if (!realInvoiceNums.isEmpty() && !llmFreeText.isBlank()) {
            Set<String> mentioned = new LinkedHashSet<>();
            Matcher m = INVOICE_NUM_PATTERN.matcher(llmFreeText);
            while (m.find()) {
                mentioned.add(m.group(1));
            }
            for (String num : mentioned) {
                if (!looksLikeInvoiceNum(num, realInvoiceNums)) {
                    continue;
                }
                if (!realInvoiceNums.contains(num)) {
                    hits.add(new SelfCheckResult.Contradiction("HALLUCINATED_INVOICE_NUM",
                            SelfCheckResult.TYPE_HALLUCINATION,
                            "LLM 输出引用了前序步骤中不存在的发票号码 " + num + "（疑似幻觉）"));
                }
            }
        }

        if (hits.isEmpty()) {
            log.debug("语义自校验通过（断言 {} 条）", ASSERTION_COUNT);
            return SelfCheckResult.pass(ASSERTION_COUNT);
        }
        log.warn("语义自校验未通过，命中 {} 条矛盾: {}", hits.size(),
                hits.stream().map(SelfCheckResult.Contradiction::assertion).toList());
        return SelfCheckResult.fail(hits, ASSERTION_COUNT);
    }

    /**
     * 汇总前序工具输出里出现的真实发票号码（用于幻觉核验的"事实基准"）。
     * <p>数据来源是 R2 落库的 {@code invoice_record} 投影——经工具输出回显，故这里的号码是客观事实。</p>
     */
    private static Set<String> collectInvoiceNums(Collection<Map<String, Object>> outputs) {        Set<String> nums = new LinkedHashSet<>();
        for (Map<String, Object> out : outputs) {
            collectInvoiceNums(out, nums);
        }
        return nums;
    }

    /** 递归收集 Map/List 中的 invoiceNum（工具输出结构各异，直接递归最稳） */
    @SuppressWarnings("unchecked")
    private static void collectInvoiceNums(Object node, Set<String> sink) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (VERIFIABLE_KEYS.contains(key) && v != null) {
                    String s = v.toString().trim();
                    if (!s.isEmpty() && s.chars().allMatch(Character::isDigit)) {
                        sink.add(s);
                    }
                } else {
                    collectInvoiceNums(v, sink);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) {
                collectInvoiceNums(o, sink);
            }
        }
    }

    /**
     * 判断某个数字串是否「疑似发票号码」。
     *
     * <p>幻觉核验只能做形态判断，故需排除与票号形态重叠的常见数字：</p>
     * <ul>
     *   <li>长度须与<b>本单真实票号</b>一致（金额、单价通常位数不同）</li>
     *   <li>排除 {@code yyyyMMdd} 日期（实测踩过：日期 {@code 20260901} 与 8 位票号同长度，
     *       被误判为幻觉票号）</li>
     * </ul>
     * <p>不做更激进的启发式：宁可漏判，也不要让自校验把正常结论误判成矛盾而触发无谓重跑。</p>
     */
    private static boolean looksLikeInvoiceNum(String num, Set<String> realInvoiceNums) {
        boolean sameShape = realInvoiceNums.stream().anyMatch(r -> r.length() == num.length());
        if (!sameShape || isCompactDate(num)) {
            return false;
        }
        return true;
    }

    /** 是否形如 {@code yyyyMMdd} 的紧凑日期 */
    private static boolean isCompactDate(String s) {
        if (s.length() != 8) {
            return false;
        }
        int month = Integer.parseInt(s.substring(4, 6));
        int day = Integer.parseInt(s.substring(6, 8));
        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    /** 把 Map/List 递归摊平为文本（仅用于幻觉核验的自由文本收集） */
    private static String flatten(Object node) {
        StringBuilder sb = new StringBuilder();
        flattenInto(node, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(Object node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                flattenInto(v, sb);
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) {
                flattenInto(o, sb);
            }
        } else {
            sb.append(node).append(' ');
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** 步骤号（可空时按 -1 处理，用于选取「最后一个 LLM 步骤」而不依赖入参顺序） */
    private static int stepNoOf(AgentTaskStep s) {
        return s.getStepNo() == null ? -1 : s.getStepNo();
    }

    private static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v != null) {
            try {
                return new BigDecimal(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // 非数字
            }
        }
        return null;
    }

    /** 供单测/日志观测：断言总数 */
    public static int assertionCount() {
        return ASSERTION_COUNT;
    }
}
