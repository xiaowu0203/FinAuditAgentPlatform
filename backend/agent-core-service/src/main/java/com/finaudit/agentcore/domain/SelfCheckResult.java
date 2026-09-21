package com.finaudit.agentcore.domain;

import java.util.List;

/**
 * 语义自校验结果（P3.8 R5，A-1 自主纠错）。
 *
 * <p><b>它解决什么问题</b>：需求标准③「自主任务拆解 / 分步执行 / 工具联动 / <b>自主纠错</b>」中，
 * 自主纠错此前<b>实质未实现</b>——只有 TOOL 步骤传输层重试 3 次（即时重发、无退避），
 * 没有任何<b>语义层</b>的「结果合法性与交叉一致性校验 → 异常自动重执行」。
 * 本结构承载确定性一致性断言的结论，并作为驱动重执行的依据。</p>
 *
 * <p><b>为什么用确定性断言而非 LLM self-critique</b>（已决策）：
 * 可单测、可解释、零额外 Token、不引入新不确定性。断言消费的是 R2/R3 落库的真实数据
 * （发票号、票据-明细差额、规则标准值），不是空转。</p>
 *
 * <p><b>为什么算「自主纠错」而非又一层规则</b>：它<b>消费 LLM 输出并对 LLM 自己下判断</b>
 * （含幻觉维度），并<b>驱动重执行</b>（重跑风控语义步骤），属 self-consistency / self-reflection 模式；
 * 同时天然产出 P4 所需指标（工具纠错次数、幻觉率）。</p>
 *
 * @param coherent       是否全部断言通过（true = 无矛盾、无幻觉）
 * @param contradictions 命中的矛盾/幻觉清单（空 = 通过）
 * @param checkedCount   实际参与校验的断言条数（便于观测"断了几条"，而非笼统的 true/false）
 */
public record SelfCheckResult(boolean coherent, List<Contradiction> contradictions, int checkedCount) {

    /** 矛盾类型：语义冲突（工具结果与 LLM 结论互相打架，或跨工具结论不一致） */
    public static final String TYPE_CONTRADICTION = "CONTRADICTION";
    /** 矛盾类型：幻觉（LLM 输出引用了前序步骤中不存在的单据号/发票号） */
    public static final String TYPE_HALLUCINATION = "HALLUCINATION";

    /** 单条矛盾/幻觉。 */
    public record Contradiction(String assertion, String type, String detail) {
    }

    public static SelfCheckResult pass(int checkedCount) {
        return new SelfCheckResult(true, List.of(), checkedCount);
    }

    public static SelfCheckResult fail(List<Contradiction> contradictions, int checkedCount) {
        return new SelfCheckResult(false, contradictions == null ? List.of() : contradictions, checkedCount);
    }

    /** 是否命中幻觉（供 P4 幻觉率指标） */
    public boolean hasHallucination() {
        return contradictions.stream().anyMatch(c -> TYPE_HALLUCINATION.equals(c.type()));
    }

    /**
     * 转为结构化问题项，供「自校验未通过」时并入工单 findings（复用 R4 的 ReviewFinding）。
     * <p>级别归 {@code RISK_HIT}：自校验不通过说明结论不可信，需人工判断而非规则性失败。</p>
     */
    public List<ReviewFinding> toFindings() {
        return contradictions.stream()
                .map(c -> ReviewFinding.ofDocument("SELF_CHECK_" + c.assertion(),
                        ReviewFinding.LEVEL_RISK_HIT,
                        "自校验未通过[" + c.assertion() + "]" + c.detail()))
                .toList();
    }

    /** 转为给 LLM 看的矛盾提示文本（重跑风控步骤时注入增强 prompt）。 */
    public String toPromptHint() {
        StringBuilder sb = new StringBuilder("【自校验发现矛盾，请重新判断】\n");
        for (Contradiction c : contradictions) {
            sb.append("- ").append(c.assertion()).append("：").append(c.detail()).append("\n");
        }
        sb.append("请基于前序步骤的真实结果修正你的结论与置信度；")
          .append("若确有依据支持原判断，请在 riskPoints 中说明理由。");
        return sb.toString();
    }

    /**
     * 转为落库用的 Map 快照（{@code agent_task.self_check_result}）。
     * <p>字段名稳定，供前端「自校验明细」直接渲染。</p>
     */
    public java.util.Map<String, Object> toResultMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("coherent", coherent);
        m.put("checkedCount", checkedCount);
        m.put("hallucination", hasHallucination());
        m.put("contradictions", contradictions.stream().map(c -> {
            java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("assertion", c.assertion());
            one.put("type", c.type());
            one.put("detail", c.detail());
            return one;
        }).toList());
        return m;
    }
}
