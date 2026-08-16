package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 财务规则（finance_rule，P2b rule_check 数据源，归属 agent-core）。
 * <p>rule_config 为结构化 JSON（仅存储，不参与 WHERE，MySQL 5.7 限制）；评估逻辑在
 * {@link com.finaudit.agentcore.service.FinanceRuleService#check}；
 * Nacos 动态刷新（P2c）+ 本地缓存 TTL（P2b 直查 DB）。</p>
 */
@Getter
@Setter
@TableName(value = "finance_rule", autoResultMap = true)
public class FinanceRule {

    @TableId(type = IdType.AUTO)
    @Schema(description = "规则主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "规则编码（唯一）")
    private String ruleCode;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则类型: TRAVEL_STANDARD/SUBSIDY_LIMIT/REIMBURSE_EXPIRE/AMOUNT_LIMIT")
    private String ruleType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "结构化规则（如 {\"threshold\":5000} / {\"maxDays\":30}）")
    private Map<String, Object> ruleConfig;

    @Schema(description = "启停: 1启用 0禁用")
    private Integer enabled;

    @Schema(description = "是否已发布 Nacos: 1已发布 0未发布")
    private Integer published;

    @Schema(description = "规则版本")
    private String version;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;
}
