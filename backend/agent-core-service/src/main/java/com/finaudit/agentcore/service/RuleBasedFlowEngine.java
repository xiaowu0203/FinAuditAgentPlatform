package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于规则的报销流程流水线规划引擎（仅REIMBURSEMENT报销单）
 * <p>
 * 根据报销任务入参，动态组装REIMBURSEMENT报销场景的Agent任务执行步骤。
 * 部分步骤为条件创建：存在附件才生成OCR票据解析；存在部门名称才生成预算核算；
 * 其余校验、风控、汇总步骤固定生成。输出TaskPlanStep步骤列表，供后续调度器执行。
 * </p>
 */
@Component
public class RuleBasedFlowEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedFlowEngine.class);

    /**
     * 组装报销场景固定流水线步骤（报销单固定流水线步骤）
     * <p>
     * 条件步骤：
     * 1. 存在附件：创建票据解析OCR步骤
     * 2. 传入deptName部门名称：创建预算核算步骤
     * 其余金额核验、规则校验、重复检测、LLM风控、LLM汇总结论为必选步骤
     * </p>
     *
     * @param task Agent任务对象，携带报销入参
     * @return 规划后的任务步骤列表 TaskPlanStep
     */
    public List<TaskPlanStep> plan(AgentTask task) {
        // 获取任务参数
        Map<String, Object> in = task.getInputParams();
        List<TaskPlanStep> steps = new ArrayList<>();

        // 1. 票据解析(OCR)：有附件才创建该步骤，提取附件id集合传给工具
        List<Object> attachmentIds = extractAttachmentIds(in.get("attachments"));
        if (!attachmentIds.isEmpty()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("reimbId", asLong(in.get("reimbId")));
            p.put("attachmentIds", attachmentIds);
            // 添加票价解析步骤（TOOL类型）
            steps.add(new TaskPlanStep("票据解析", "TOOL", "ocr_extract", p,
                    AgentRole.DOCUMENT_PARSER.name()));
        }

        // 2. 预算核算（有部门才建；P3.5b 透传 deptId/reimbId 供工具越权校验）
        Object dept = in.get("deptName");
        if (dept != null && !dept.toString().isBlank()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("deptName", dept.toString());
            p.put("deptId", in.get("deptId"));
            p.put("reimbId", asLong(in.get("reimbId")));
            p.put("claimDate", claimDateStr(in.get("claimDate")));
            p.put("amount", in.get("claimedTotal"));
            // 添加预算核算步骤（TOOL类型）
            steps.add(new TaskPlanStep("预算核算", "TOOL", "budget_query", p,
                    AgentRole.BUDGET_CALCULATOR.name()));
        }

        // 3.金额核验：投影报销明细，组装明细列表+申报总额，做总额和明细一致性校验
        Map<String, Object> av = new LinkedHashMap<>();
        av.put("items", projectAmounts(in.get("items")));
        av.put("claimedTotal", in.get("claimedTotal"));
        // 添加金额核验步骤（TOOL类型）
        steps.add(new TaskPlanStep("金额核验", "TOOL", "amount_verify", av,
                AgentRole.RULE_VALIDATOR.name()));

        // 4.规则校验：透传报销类型、申报日期、总金额、明细，用于差旅、限额等规则判断
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("expenseType", in.get("expenseType"));
        rc.put("claimDate", claimDateStr(in.get("claimDate")));
        rc.put("totalAmount", in.get("claimedTotal"));
        rc.put("items", in.get("items"));
        // 添加规则校验步骤（TOOL类型）
        steps.add(new TaskPlanStep("规则校验", "TOOL", "rule_check", rc,
                AgentRole.RULE_VALIDATOR.name()));

        // 5.重复报销检测：传入报销单号，检测历史是否存在疑似重复报销单据
        Map<String, Object> dc = new LinkedHashMap<>();
        dc.put("reimbId", asLong(in.get("reimbId")));
        // 添加重复报销检测步骤（TOOL类型）
        steps.add(new TaskPlanStep("重复报销检测", "TOOL", "duplicate_check", dc,
                AgentRole.RISK_AUDITOR.name()));

        // 6.风控语义判断LLM步骤，无工具名，执行时由executeLlmStep组装上下文prompt
        // 添加风控语义判断步骤（LLM类型）
        steps.add(new TaskPlanStep("风控语义判断", "LLM", null, null,
                AgentRole.RISK_AUDITOR.name()));

        // 7.审核结论汇总LLM步骤，调度器角色，综合全部工具输出输出最终decision
        // 添加审核结论汇总步骤（LLM类型）
        steps.add(new TaskPlanStep("审核结论汇总", "LLM", null, null,
                AgentRole.SCHEDULER.name()));

        log.info("任务[{}] REIMBURSEMENT 规则流水线规划完成，共 {} 步", task.getId(), steps.size());
        return steps;
    }

    /**
     * 附件对象列表提取id，输出扁平化id集合
     * <p>
     * 输入attachments数组元素为map结构{id,fileType...}，只提取id；
     * 下游工具会再做一次asLong，兼容Integer/Long两种id类型
     * </p>
     *
     * @param attachments 原始附件对象列表
     * @return 附件id列表，无数据返回空集合
     */
    private static List<Object> extractAttachmentIds(Object attachments) {
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

    /**
     * 报销明细投影，输出amount_verify工具需要最小结构 [{name,amount}]
     * <p>只保留名称、金额两个字段，过滤明细中其他冗余字段</p>
     *
     * @param items 原始报销明细数组
     * @return 投影之后的明细列表
     */
    private static List<Map<String, Object>> projectAmounts(Object items) {
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

    /**
     * 申报日期统一转换 yyyy‑MM‑dd 字符串格式
     * <p>
     * 支持LocalDate对象直接toString；
     * 如果是字符串，超过10位做截断，防御数据库回读超长字符串；
     * 其他类型直接toString兜底。
     * </p>
     *
     * @param v 原始日期对象，可以是LocalDate、String或其他
     * @return yyyy‑MM‑dd格式字符串，null输入返回null
     */
    private static String claimDateStr(Object v) {
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof String s) {
            return s.length() > 10 ? s.substring(0, 10) : s;
        }
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 通用安全转换Long工具
     * <p>支持Number子类、数字字符串；解析失败返回null，交给下游业务做兜底处理</p>
     *
     * @param v 待转换对象
     * @return Long值，解析失败返回null
     */
    private static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v != null) {
            try {
                return Long.valueOf(v.toString());
            } catch (NumberFormatException ignored) {
                // 非数字字符串，返回null，由下游做兼容
            }
        }
        return null;
    }
}
