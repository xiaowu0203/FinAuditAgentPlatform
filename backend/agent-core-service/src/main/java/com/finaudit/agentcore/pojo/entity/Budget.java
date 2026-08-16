package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 部门预算（budget，P2b budget_query 工具数据源，归属 agent-core）。
 * <p>按 部门 + 周期（YYYY-MM）唯一；total/used 金额一律 Decimal（CLAUDE.md §5.3）；
 * used_amount 审核通过后累加（P3 审批流），本阶段只读。</p>
 */
@Getter
@Setter
@TableName("budget")
public class Budget {

    @TableId(type = IdType.AUTO)
    @Schema(description = "预算主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "部门")
    private String deptName;

    @Schema(description = "预算周期 YYYY-MM")
    private String period;

    @Schema(description = "预算总额")
    private BigDecimal totalBudget;

    @Schema(description = "已用额度")
    private BigDecimal usedAmount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;
}
