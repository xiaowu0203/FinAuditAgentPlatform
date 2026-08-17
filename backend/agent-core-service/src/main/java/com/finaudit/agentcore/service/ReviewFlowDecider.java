package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.FlowDecision;
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
 * 扫描规则校验、预算校验、重复校验、金额校验、LLM风控审计步骤，收集风险原因；
 * 只要命中任意风险项，返回人工复核；无风险全部通过则自动放行。
 * <p>
 * 注意：仅统计有输出的SUCCESS步骤；步骤缺失、无输出按未触发处理；
 * LLM规划任务 resume 恢复执行场景，允许偏向AUTO_PASS行为。
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
     * 遍历Agent任务步骤，收集各类风控命中原因；
     * 只要存在风险原因，返回 needReview；无任何风险返回 autoPass。
     * 仅处理有输出的步骤；步骤缺失/输出为空直接跳过。
     * </p>
     * @param steps Agent任务执行步骤列表
     * @return FlowDecision 流程决策结果（自动通过 / 需要复核，携带风险原因列表）
     */
    public FlowDecision decide(List<AgentTaskStep> steps) {
        List<String> reasons = new ArrayList<>();

        // 遍历每一步Agent执行结果，收集风险点
        for (AgentTaskStep s : steps) {
            // 无输出直接跳过，视为该步骤未实际执行
            if (s.getOutput() == null) {
                continue;
            }
            Map<String, Object> out = s.getOutput();
            String tool = s.getToolName();

            // ---------------- rule_check 规则校验工具 ----------------
            if ("rule_check".equals(tool)) {
                // 遍历规则命中列表，识别金额超限规则 OVER_LIMIT
                for (Object h : asList(out.get("hits"))) {
                    if (h instanceof Map<?, ?> hit && "AMOUNT_LIMIT".equals(hit.get("ruleType"))) {
                        reasons.add("OVER_LIMIT:" + safe(hit.get("ruleName")) + " 超标");
                    }
                }
                // 全局规则校验超标标记，标记为RULE_FAIL
                if (Boolean.TRUE.equals(out.get("overLimit"))) {
                    reasons.add("RULE_FAIL:规则校验超标");
                }
            }
            // ---------------- budget_query 部门预算查询工具 ----------------
            else if ("budget_query".equals(tool)) {
                // 预算超出标记
                if (Boolean.TRUE.equals(out.get("exceedsBudget"))) {
                    reasons.add("RULE_FAIL:部门预算超支");
                }
            }
            // ---------------- duplicate_check 重复报销检测工具 --------------
            else if ("duplicate_check".equals(tool)) {
                // 疑似重复报销标记
                if (Boolean.TRUE.equals(out.get("suspected"))) {
                    reasons.add("RISK_HIT:疑似重复报销");
                }
            }
            // ---------------- amount_verify 金额校验工具 ----------------
            else if ("amount_verify".equals(tool)) {
                // 明细金额与申报总额不匹配
                if (Boolean.FALSE.equals(out.get("match"))) {
                    reasons.add("RISK_HIT:明细金额与申报总额不符");
                }
            }
            // ---------------- LLM风控审计Agent步骤 ----------------
            else if ("LLM".equalsIgnoreCase(s.getStepType())
                    && AgentRole.RISK_AUDITOR.name().equals(s.getAgentRole())) {
                // LLM输出uncertain存疑标记（幻觉拦截占位）
                if (Boolean.TRUE.equals(out.get("uncertain"))) {
                    reasons.add("RISK_HIT:风控语义判断存疑");
                } else {
                    Object conf = out.get("confidence");
                    // 置信度字段缺失
                    if (conf == null) {
                        reasons.add("RISK_HIT:风控置信度缺失");
                    }
                    // 置信度低于阈值，进入复核
                    else if (toDecimal(conf).compareTo(CONFIDENCE_THRESHOLD) < 0) {
                        reasons.add("RISK_HIT:风控置信度低于 0.7");
                    }
                }
            }
        }
        // 提取最后一次LLM汇总结论：非 APPROVE（REJECT/NEED_INFO）→ 人工确认
        String decision = extractDecision(steps);
        if (decision != null && !"APPROVE".equalsIgnoreCase(decision)) {
            reasons.add("LLM_DECISION:" + decision.toUpperCase());
        }

        // 无风险原因：自动通过；存在风险原因：需要人工复核
        return reasons.isEmpty() ? FlowDecision.autoPass() : FlowDecision.needReview(reasons);
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

    /**
     * 通用对象安全转换BigDecimal
     * <p>支持BigDecimal、Number、字符串；转换失败/非数字，直接返回阈值兜底（不触发风险）</p>
     *
     * @param v 原始对象，置信度原始值
     * @return BigDecimal数值；解析失败返回CONFIDENCE_THRESHOLD做兜底
     */
    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (v != null) {
            try {
                return new BigDecimal(v.toString());
            } catch (NumberFormatException ignored) {
                // 解析失败，按达到阈值兜底，不触发风险
            }
        }
        return CONFIDENCE_THRESHOLD;
    }
}
