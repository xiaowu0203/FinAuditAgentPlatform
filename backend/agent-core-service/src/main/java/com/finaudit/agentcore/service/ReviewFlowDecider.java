package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.FlowDecision;
import com.finaudit.agentcore.domain.ReviewFinding;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审核流程决策器（仅报销单 REIMBURSEMENT）
 * <p>
 * 根据Agent任务各步骤输出结果，执行风控规则判断，输出自动通过 / 需要人工复核决策。
 * 扫描规则校验、预算校验、重复校验、金额校验、票据核验步骤，收集风险原因并产出
 * <b>结构化问题项</b>（{@link ReviewFinding}）；只要命中任意问题项即返回人工复核。
 * <p>
 * <b>P3.8 R4 起产出结构化 findings</b>：{@link FlowDecision#findings()} 是权威数据，
 * {@link FlowDecision#reviewReasons()} 由它派生（{@code "{LEVEL}:{说明}"}），
 * 因此既有的 trigger_type 前缀约定与 {@code risk_desc} 消费方不受影响。
 * 结构化字段（明细行下标 / 标准值 / 实际值 / 差额 / 建议）直接支撑「驳回重提引导」——
 * 提交人能看到该改哪一行、改成多少。
 * <p>
 * 注意：仅统计有输出的SUCCESS步骤；步骤缺失、无输出按未触发处理；
 * LLM规划任务 resume 恢复场景，允许偏向AUTO_PASS行为。
 * </p>
 */
@Component
public class ReviewFlowDecider {
    /**
     * 风控LLM置信度阈值
     * <p>低于该阈值，或置信度字段缺失，判定为存疑，强制进入人工复核</p>
     */
    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.7");

    /**
     * 判定流水线结果分支
     * <p>
     * 遍历Agent任务步骤，收集结构化问题项；
     * 只要存在问题项，返回 needReview；无任何问题返回 autoPass。
     * 仅处理有输出的步骤；步骤缺失/输出为空直接跳过。
     * </p>
     * @param steps Agent任务执行步骤列表
     * @return FlowDecision 流程决策结果（自动通过 / 需要复核，携带结构化问题项与派生原因串）
     */
    public FlowDecision decide(List<AgentTaskStep> steps) {
        List<ReviewFinding> findings = new ArrayList<>();

        // 遍历每一步Agent执行结果，收集问题项
        for (AgentTaskStep s : steps) {
            // 无输出直接跳过，视为该步骤未实际执行
            if (s.getOutput() == null) {
                continue;
            }
            Map<String, Object> out = s.getOutput();
            String tool = s.getToolName();

            // ---------------- rule_check 规则校验工具 ----------------
            if ("rule_check".equals(tool)) {
                collectRuleHits(findings, out);
            }
            // ---------------- budget_query 部门预算查询工具 ----------------
            else if ("budget_query".equals(tool)) {
                if (Boolean.TRUE.equals(out.get("exceedsBudget"))) {
                    findings.add(ReviewFinding.ofDocument("BUDGET_EXCEEDED", ReviewFinding.LEVEL_RULE_FAIL,
                            "部门预算超支，请调整申报金额或联系部门负责人确认预算"));
                }
            }
            // ---------------- duplicate_check 重复报销检测工具 ----------------
            else if ("duplicate_check".equals(tool)) {
                collectDuplicate(findings, out);
            }
            // ---------------- amount_verify 金额校验工具 ----------------
            else if ("amount_verify".equals(tool)) {
                if (Boolean.FALSE.equals(out.get("match"))) {
                    // 差额可直接从工具输出取（claimedTotal - actualTotal 由工具给出）
                    findings.add(ReviewFinding.ofDocumentAmount("AMOUNT_MISMATCH", ReviewFinding.LEVEL_RISK_HIT,
                            decimal(out.get("claimedTotal")), decimal(out.get("actualTotal")),
                            "明细金额与申报总额不符，请核对明细"));
                }
            }
            // ---------------- invoice_match 票据-明细交叉核验工具（P3.8 R3-6） ----------------
            else if ("invoice_match".equals(tool)) {
                collectInvoiceMatch(findings, out);
            }
            // ---------------- LLM风控审计Agent步骤 ----------------
            else if ("LLM".equalsIgnoreCase(s.getStepType())
                    && AgentRole.RISK_AUDITOR.name().equals(s.getAgentRole())) {
                if (Boolean.TRUE.equals(out.get("uncertain"))) {
                    findings.add(ReviewFinding.ofDocument("RISK_UNCERTAIN", ReviewFinding.LEVEL_RISK_HIT,
                            "风控语义判断存疑，请人工复核票据与业务背景"));
                } else {
                    Object conf = out.get("confidence");
                    if (conf == null) {
                        findings.add(ReviewFinding.ofDocument("RISK_CONFIDENCE_MISSING", ReviewFinding.LEVEL_RISK_HIT,
                                "风控置信度缺失，请人工复核"));
                    } else if (toDecimal(conf).compareTo(CONFIDENCE_THRESHOLD) < 0) {
                        findings.add(ReviewFinding.ofDocument("RISK_CONFIDENCE_LOW", ReviewFinding.LEVEL_RISK_HIT,
                                "风控置信度低于 0.7，请人工复核"));
                    }
                }
            }
        }
        // 提取最后一次LLM汇总结论：非 APPROVE（REJECT/NEED_INFO）→ 人工确认
        // reason 前缀刻意用 LLM_DECISION 而非 RISK_HIT：这是 FlowDecision 文档化的前缀约定，
        // 也是既有消费方（工单展示/测试）依赖的格式，不能因为结构化改造顺手改掉
        String decision = extractDecision(steps);
        if (decision != null && !"APPROVE".equalsIgnoreCase(decision)) {
            findings.add(new ReviewFinding("LLM_DECISION", ReviewFinding.LEVEL_RISK_HIT, null, null,
                    null, null, null,
                    "审核结论为 " + decision.toUpperCase() + "，需人工确认"));
        }

        return findings.isEmpty() ? FlowDecision.autoPass() : FlowDecision.needReview(findings);
    }

    /**
     * 收集 {@code rule_check} 命中项为结构化问题项。
     * <p>{@code AMOUNT_LIMIT} 命中产出 {@code OVER_LIMIT} 级别（工单 triggerType 优先取它），
     * 与既有「OVER_LIMIT 优先于 RULE_FAIL」的解析约定保持一致；同时按既有契约另产出一条
     * RULE_FAIL 级别的规则超标项。</p>
     */
    private static void collectRuleHits(List<ReviewFinding> findings, Map<String, Object> out) {
        // ⚠️ 兜底判定必须看「是否有具体命中项被处理」，不能看「是否已产出 RULE_FAIL 级别」：
        //    AMOUNT_LIMIT 命中产出的是 OVER_LIMIT 级别，按后者判断会漏判，导致多补一条重复的 RULE_FAIL。
        boolean anyHit = false;
        for (Object h : asList(out.get("hits"))) {
            if (!(h instanceof Map<?, ?> hit)) {
                continue;
            }
            anyHit = true;
            String ruleType = safe(hit.get("ruleType"));
            String ruleName = safe(hit.get("ruleName"));
            Integer itemIndex = toInt(hit.get("itemIndex"));
            String itemName = safe(hit.get("itemName"));
            BigDecimal expected = decimal(hit.get("expected"));
            BigDecimal actual = decimal(hit.get("actual"));
            String suggestion = "请按标准 " + (expected == null ? "-" : expected.stripTrailingZeros().toPlainString())
                    + " 调整该明细金额，或补充说明材料";

            if ("AMOUNT_LIMIT".equals(ruleType)) {
                // 大额限额：单据级，标准=限额，实际=申报总额
                findings.add(ReviewFinding.ofDocumentAmount("AMOUNT_LIMIT", ReviewFinding.LEVEL_OVER_LIMIT,
                        expected, actual, ruleName + " 超标，请调整申报总额或走大额审批流程"));
                // 既有契约：AMOUNT_LIMIT 命中同时属「规则超标」，另行产出一条 RULE_FAIL
                // （保留原实现的双条语义——OVER_LIMIT 决定工单 triggerType，RULE_FAIL 供规则侧展示）
                findings.add(ReviewFinding.ofDocument("RULE_FAIL", ReviewFinding.LEVEL_RULE_FAIL,
                        ruleName + " 超标，请核对申报明细是否合规"));
            } else if (itemIndex != null || itemName != null) {
                // 明细级（差旅住宿/交通、补贴、时效）
                findings.add(ReviewFinding.ofItem(ruleType, ReviewFinding.LEVEL_RULE_FAIL,
                        itemIndex, itemName, expected, actual, suggestion));
            } else {
                // 未知/单据级规则命中：保留告警，不强造定位
                findings.add(ReviewFinding.ofDocumentAmount(ruleType, ReviewFinding.LEVEL_RULE_FAIL,
                        expected, actual, ruleName + " 命中，请人工复核"));
            }
        }
        // 全局超标标记但没有任何具体命中项（历史/兜底路径）→ 补一条规则性失败
        if (Boolean.TRUE.equals(out.get("overLimit")) && !anyHit) {
            findings.add(ReviewFinding.ofDocument("RULE_FAIL", ReviewFinding.LEVEL_RULE_FAIL,
                    "规则校验超标，请核对申报明细"));
        }
    }

    /**
     * 收集 {@code duplicate_check} 结果。
     * <p>仅发票号硬命中（同一张票已报销）触发风控；中置信只作展示不阻断——B-4 误报的根治。
     * 兼容：老输出无 {@code suspectedHigh} 字段时回落看 {@code suspected}，行为不突变。</p>
     */
    private static void collectDuplicate(List<ReviewFinding> findings, Map<String, Object> out) {
        Object highFlag = out.get("suspectedHigh");
        boolean high = highFlag != null
                ? Boolean.TRUE.equals(highFlag)
                : Boolean.TRUE.equals(out.get("suspected"));
        if (!high) {
            return;
        }
        // 有明细命中项时逐条定位到「同一张票所属的其他单」，便于人工比对
        List<?> duplicates = asList(out.get("duplicates"));
        boolean added = false;
        for (Object d : duplicates) {
            if (!(d instanceof Map<?, ?> dup) || !"LEVEL_HIGH".equals(safe(dup.get("dupLevel")))) {
                continue;
            }
            findings.add(ReviewFinding.ofDocument("DUPLICATE_INVOICE", ReviewFinding.LEVEL_RISK_HIT,
                    "发票 " + safe(dup.get("invoiceCode")) + "/" + safe(dup.get("invoiceNum"))
                            + " 已由报销单 " + safe(dup.get("reimbNo")) + " 报销过，请确认是否重复提交"));
            added = true;
        }
        if (!added) {
            findings.add(ReviewFinding.ofDocument("DUPLICATE_INVOICE", ReviewFinding.LEVEL_RISK_HIT,
                    "存在发票号硬命中（同一张票已报销），请确认是否重复提交"));
        }
    }

    /**
     * 收集 {@code invoice_match} 异常为结构化问题项（P3.8 R3-6）。
     */
    private static void collectInvoiceMatch(List<ReviewFinding> findings, Map<String, Object> out) {
        if (!Boolean.FALSE.equals(out.get("match"))) {
            return;
        }
        for (Object f : asList(out.get("flags"))) {
            if (!(f instanceof Map<?, ?> flag)) {
                continue;
            }
            String code = safe(flag.get("code"));
            String message = safe(flag.get("message"));
            if ("AMOUNT_MISMATCH".equals(code)) {
                findings.add(ReviewFinding.ofDocumentAmount("AMOUNT_MISMATCH", ReviewFinding.LEVEL_RULE_FAIL,
                        decimal(out.get("invoiceTotal")), decimal(out.get("claimTotal")),
                        "申报合计超过票面合计，请核对明细是否虚报"));
            } else {
                findings.add(ReviewFinding.ofDocument(code, ReviewFinding.LEVEL_RULE_FAIL, message));
            }
        }
        if (findings.stream().noneMatch(f -> "AMOUNT_MISMATCH".equals(f.code())
                || "ITEM_EXCEEDS_INVOICE".equals(f.code()) || "NO_INVOICE".equals(f.code()))) {
            findings.add(ReviewFinding.ofDocument("INVOICE_MISMATCH", ReviewFinding.LEVEL_RULE_FAIL,
                    "票据与明细不一致，请人工复核"));
        }
    }

    /**
     * 提取最后一个LLM步骤输出中的decision汇总结论
     * <p>倒序扫描步骤列表，取最后一个LLM类型步骤的decision字段；
     * 兼容Resume恢复任务场景，可拿到SCHEDULER汇总输出。</p>
     *
     * @param steps Agent任务步骤列表
     * @return decision字符串，无有效字段返回null
     */
    private static String extractDecision(List<AgentTaskStep> steps) {
        // 倒序，优先取最新执行的LLM输出
        for (int i = steps.size() - 1; i >= 0; i--) {
            AgentTaskStep s = steps.get(i);
            if ("LLM".equalsIgnoreCase(s.getStepType()) && s.getOutput() instanceof Map<?, ?>) {
                Object d = ((Map<?, ?>) s.getOutput()).get("decision");
                return d == null ? null : d.toString();
            }
        }
        return null;
    }

    /**
     * 安全转为List对象，非List类型返回空集合，避免空指针与类型转换异常
     *
     * @param v 原始对象，可以为null或任意类型
     * @return 列表对象；入参不是List返回空List
     */
    private static List<?> asList(Object v) {
        return v instanceof List<?> list ? list : List.of();
    }

    /**
     * 对象安全转字符串，null返回空字符串
     *
     * @param v 任意对象
     * @return 非null返回toString；null返回空串
     */
    private static String safe(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 对象安全转 Integer（兼容 Number 与数字串），失败返回 null */
    private static Integer toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.valueOf(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // 非数字，返回 null
            }
        }
        return null;
    }

    /** 对象安全转 BigDecimal（兼容 Number 与数字串），失败返回 null */
    private static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v != null) {
            try {
                return new BigDecimal(v.toString().replace(",", "").trim());
            } catch (NumberFormatException ignored) {
                // 非数字，返回 null
            }
        }
        return null;
    }

    /**
     * 通用对象安全转换BigDecimal
     * <p>支持BigDecimal、Number、字符串；转换失败/非数字，直接返回阈值兜底（不触发风险）</p>
     *
     * @param v 原始对象，置信度原始值
     * @return BigDecimal数值；解析失败返回CONFIDENCE_THRESHOLD做兜底
     */
    private static BigDecimal toDecimal(Object v) {
        BigDecimal d = decimal(v);
        return d == null ? CONFIDENCE_THRESHOLD : d;
    }
}
