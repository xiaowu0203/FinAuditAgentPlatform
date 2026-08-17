package com.finaudit.agentcore.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * LLM风控评估结果记录
 * <p>
 * 由RISK_AUDITOR风控审计Agent输出，承载语义风险判定完整信息，
 * </p>
 *
 * @param riskLevel     风险等级，示例值：LOW / MEDIUM / HIGH，代表整体风险级别
 * @param confidence    置信度，取值范围0~1，数值越高代表AI判断结果可信度越高
 * @param uncertain     是否判断存疑；true=信息不足、语义模糊、无法给出确定结论，强制人工复核
 * @param summary       风控评估总结文本，对本次风险判定的一句话描述
 * @param riskPoints    风险点明细列表，每条为一条具体风险描述文本
 * TODO(P4 幻觉拦截 → 正式评估体系)：此处仅为占位标记，P4 引入幻觉检测规则与评估大盘。</p>
 */
public record RiskAssessment(
        @Schema(description = "风险等级") String riskLevel,
        @Schema(description = "置信度") BigDecimal confidence,
        @Schema(description = "是否判断存疑") boolean uncertain,
        @Schema(description = "风控评估总结文本，对本次风险判定的一句话描述") String summary,
        @Schema(description = "风险点明细列表，每条为一条具体风险描述文本") List<String> riskPoints) {
}
