package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * <p>按 部门(dept_id) + 周期（YYYY-MM）唯一（P3.5b uk 切换）；total/used 金额一律 Decimal；
 * dept_name 为冗余显示列（权威为 dept_id）；used_amount 审核通过后累加（P3 审批流），本阶段只读。</p>
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

    @Schema(description = "部门（冗余显示；权威为 dept_id）")
    private String deptName;

    @Schema(description = "部门ID")
    private Long deptId;

    @Schema(description = "预算周期 YYYY-MM")
    private String period;

    @Schema(description = "预算总额")
    private BigDecimal totalBudget;

    @Schema(description = "已用额度")
    private BigDecimal usedAmount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    /** 更新时间（P3.8 R9-2 修复 R2-10 遗留）：标 fill=INSERT_UPDATE，使 updateById/实体更新能刷新该列，
     * 否则实体里读出的旧值会被写回 SET、抑制 MySQL 的 ON UPDATE CURRENT_TIMESTAMP。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;
}
