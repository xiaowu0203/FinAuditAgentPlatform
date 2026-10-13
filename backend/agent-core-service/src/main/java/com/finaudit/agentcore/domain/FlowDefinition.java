package com.finaudit.agentcore.domain;

import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.enums.StepType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流水线定义（P3.8 R6-5）：把一条业务流水线的步骤结构声明为数据。
 *
 * <p>本轮只提供 Java 内默认定义（{@link #reimbursementDefault()}），
 * 不引入 DDL 表——声明来源后续可整体替换为 Nacos / DB，{@link #materialize} 的契约不变。</p>
 *
 * <p><b>报销流水线的两条设计约束</b>（来自前三阶段的取舍，改结构时别丢）：</p>
 * <ol>
 *   <li>票据核验（invoice_match）必须紧跟规则校验之后：此时限额已判完，
 *       正好用票面数据校验明细是否虚报；无附件时不生成（无票据无从核验）。</li>
 *   <li>LLM 步骤只保留两个且都在末尾：风控语义判断（RISK_AUDITOR）→ 审核结论汇总（SCHEDULER）。
 *       自校验的结论对比依赖「最后一个 LLM 步骤」产出汇总结论，故汇总步骤必须最后。</li>
 * </ol>
 */
public final class FlowDefinition {

    private final String name;
    private final List<FlowStepDefinition> steps;

    public FlowDefinition(String name, List<FlowStepDefinition> steps) {
        this.name = name;
        this.steps = steps.stream()
                .sorted(Comparator.comparingInt(FlowStepDefinition::order))
                .toList();
    }

    public String name() {
        return name;
    }

    /** 声明本身（只读，供文档/测试核对结构） */
    public List<FlowStepDefinition> steps() {
        return steps;
    }

    /**
     * 按声明物化规划步骤：条件不满足的步骤被跳过，返回列表顺序即执行顺序
     * （{@code AgentTaskStepService.insertPlan} 按列表顺序编号落库）。
     */
    public List<TaskPlanStep> materialize(Map<String, Object> input) {
        List<TaskPlanStep> plan = new ArrayList<>();
        for (FlowStepDefinition def : steps) {
            TaskPlanStep step = def.materialize(input);
            if (step != null) {
                plan.add(step);
            }
        }
        return plan;
    }

    /**
     * 报销单审核默认流水线（原 {@code RuleBasedFlowEngine.plan} 的等价声明）。
     * <p>顺序：票据解析 → 预算核算 → 金额核验 → 规则校验 → 票据核验 → 重复检测 → 风控语义 → 结论汇总。</p>
     */
    public static FlowDefinition reimbursementDefault() {
        return new FlowDefinition("REIMBURSEMENT_DEFAULT", List.of(
                FlowStepDefinition.conditional(1, "票据解析", StepType.TOOL, "ocr_extract",
                        AgentRole.DOCUMENT_PARSER.name(),
                        in -> !attachmentIds(in).isEmpty(),
                        in -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("reimbId", asLong(in.get("reimbId")));
                            p.put("attachmentIds", attachmentIds(in));
                            return p;
                        }),
                FlowStepDefinition.conditional(2, "预算核算", StepType.TOOL, "budget_query",
                        AgentRole.BUDGET_CALCULATOR.name(),
                        in -> {
                            Object dept = in.get("deptName");
                            return dept != null && !dept.toString().isBlank();
                        },
                        in -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("deptName", String.valueOf(in.get("deptName")));
                            p.put("deptId", in.get("deptId"));
                            p.put("reimbId", asLong(in.get("reimbId")));
                            p.put("claimDate", claimDateStr(in.get("claimDate")));
                            p.put("amount", in.get("claimedTotal"));
                            return p;
                        }),
                FlowStepDefinition.always(3, "金额核验", StepType.TOOL, "amount_verify",
                        AgentRole.RULE_VALIDATOR.name(),
                        in -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("items", projectAmounts(in.get("items")));
                            p.put("claimedTotal", in.get("claimedTotal"));
                            return p;
                        }),
                FlowStepDefinition.always(4, "规则校验", StepType.TOOL, "rule_check",
                        AgentRole.RULE_VALIDATOR.name(),
                        in -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("expenseType", in.get("expenseType"));
                            p.put("claimDate", claimDateStr(in.get("claimDate")));
                            p.put("totalAmount", in.get("claimedTotal"));
                            p.put("items", in.get("items"));
                            return p;
                        }),
                FlowStepDefinition.conditional(5, "票据核验", StepType.TOOL, "invoice_match",
                        AgentRole.RULE_VALIDATOR.name(),
                        in -> !attachmentIds(in).isEmpty(),
                        in -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("reimbId", asLong(in.get("reimbId")));
                            p.put("items", projectAmounts(in.get("items")));
                            p.put("claimedTotal", in.get("claimedTotal"));
                            return p;
                        }),
                FlowStepDefinition.always(6, "重复报销检测", StepType.TOOL, "duplicate_check",
                        AgentRole.RISK_AUDITOR.name(),
                        in -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("reimbId", asLong(in.get("reimbId")));
                            return p;
                        }),
                FlowStepDefinition.always(7, "风控语义判断", StepType.LLM, null,
                        AgentRole.RISK_AUDITOR.name(), in -> null),
                FlowStepDefinition.always(8, "审核结论汇总", StepType.LLM, null,
                        AgentRole.SCHEDULER.name(), in -> null)));
    }

    // ---------- 入参投影的公共小工具（原 RuleBasedFlowEngine 私有方法上移，保持行为一致） ----------

    /** 附件对象列表提取 id（输入为 {@code [{id,fileType…}]}，只取 id；兼容 Integer/Long） */
    static List<Object> attachmentIds(Map<String, Object> in) {
        Object attachments = in.get("attachments");
        if (!(attachments instanceof List<?> list)) {
            return List.of();
        }
        List<Object> ids = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("id") != null) {
                ids.add(m.get("id"));
            }
        }
        return ids;
    }

    /** 报销明细投影：只保留 name/amount 两个字段（amount_verify/invoice_match 的最小结构） */
    static List<Map<String, Object>> projectAmounts(Object items) {
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", m.get("name"));
                item.put("amount", m.get("amount"));
                out.add(item);
            }
        }
        return out;
    }

    /** 申报日期统一为 yyyy-MM-dd 字符串（LocalDate 直接 toString；超长字符串截断防御） */
    static String claimDateStr(Object v) {
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof String s) {
            return s.length() > 10 ? s.substring(0, 10) : s;
        }
        return v == null ? null : String.valueOf(v);
    }

    /** 安全转 Long（失败返回 null，由下游业务兜底） */
    static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v != null) {
            try {
                return Long.valueOf(v.toString());
            } catch (NumberFormatException ignored) {
                // 非数字，返回 null
            }
        }
        return null;
    }
}
