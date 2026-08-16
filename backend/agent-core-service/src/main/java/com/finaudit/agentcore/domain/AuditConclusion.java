package com.finaudit.agentcore.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 审核结论（LLM 汇总步骤的结构化输出）。
 * <p>由 Spring AI {@code BeanOutputConverter} 依据本类型生成 JSON Schema，约束模型输出形状；
 * 落库为 {@code agent_task_step.output} JSON（经 Jackson 转换后的 Map）。</p>
 * <p>金额一律 {@link BigDecimal}（禁止 float/double）；可空字段仅在有量化依据时填充，
 * 未执行金额核验或入参不足时为 {@code null}。</p>
 *
 * @param summary       审核结论文本
 * @param decision      审核处置：APPROVE（通过）/ REJECT（驳回）/ NEED_INFO（需补充材料），取值由提示词约束
 * @param matched       金额是否一致（仅金额核验场景）
 * @param claimedTotal  申报总额（可空）
 * @param verifiedTotal 核验总额（可空）
 * @param diff          差额（核验总额 - 申报总额，可空）
 * @param issues        发现的问题清单（可空）
 * @param missingFields 缺失字段/材料清单（可空）
 */
public record AuditConclusion(
        String summary,
        String decision,
        Boolean matched,
        BigDecimal claimedTotal,
        BigDecimal verifiedTotal,
        BigDecimal diff,
        List<String> issues,
        List<String> missingFields
) {
}
