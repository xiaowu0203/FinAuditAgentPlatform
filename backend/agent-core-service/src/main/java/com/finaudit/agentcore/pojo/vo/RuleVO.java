package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.FinanceRule;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 财务规则（配置管理响应）。
 * <p>published 标注生效状态：1 已发布生效（在 Nacos 生效集）/ 0 未发布草稿（改后需重新发布）。</p>
 */
@Data
public class RuleVO {

    @Schema(description = "规则主键")
    private Long id;

    @Schema(description = "规则编码（唯一，创建后不可改）")
    private String ruleCode;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则类型")
    private String ruleType;

    @Schema(description = "结构化规则配置")
    private Map<String, Object> ruleConfig;

    @Schema(description = "启停: 1启用 0禁用")
    private Integer enabled;

    @Schema(description = "是否已发布 Nacos: 1生效 0草稿")
    private Integer published;

    @Schema(description = "规则版本")
    private String version;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static RuleVO from(FinanceRule rule) {
        RuleVO vo = new RuleVO();
        vo.setId(rule.getId());
        vo.setRuleCode(rule.getRuleCode());
        vo.setRuleName(rule.getRuleName());
        vo.setRuleType(rule.getRuleType());
        vo.setRuleConfig(rule.getRuleConfig());
        vo.setEnabled(rule.getEnabled());
        vo.setPublished(rule.getPublished());
        vo.setVersion(rule.getVersion());
        vo.setCreatedAt(rule.getCreatedAt());
        vo.setUpdatedAt(rule.getUpdatedAt());
        return vo;
    }
}
