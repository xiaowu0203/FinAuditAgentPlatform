package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.pojo.dto.RuleSaveRequest;
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
    @Schema(description = "逻辑删除标记（0 未删 / 主键id 已删；删除须自定义 SET deleted=id，禁用 MP 默认写 1，否则与 uk_rule_type 唯一索引冲突）")
    private Long deleted;

    /** 默认版本号 */
    private static final String DEFAULT_VERSION = "1.0";

    /**
     * 由新增请求构造规则（初始为草稿：published=0，需发布才生效；enabled 空默认启用）。
     */
    public static FinanceRule from(RuleSaveRequest request, Long tenantId) {
        FinanceRule rule = new FinanceRule();
        rule.setTenantId(tenantId);
        rule.setRuleCode(request.ruleCode());
        rule.setRuleName(request.ruleName());
        rule.setRuleType(request.ruleType());
        rule.setRuleConfig(request.ruleConfig() == null ? Map.of() : request.ruleConfig());
        rule.setEnabled(request.enabled() == null ? 1 : request.enabled());
        rule.setPublished(0);
        rule.setVersion(DEFAULT_VERSION);
        return rule;
    }

    /**
     * 用修改请求合并更新既有规则；ruleConfig / enabled 空值不覆盖（走 MyBatis 非空更新）。
     */
    public void apply(RuleSaveRequest request) {
        this.ruleName = request.ruleName();
        this.ruleType = request.ruleType();
        if (request.ruleConfig() != null) {
            this.ruleConfig = request.ruleConfig();
        }
        if (request.enabled() != null) {
            this.enabled = request.enabled();
        }
    }
}
