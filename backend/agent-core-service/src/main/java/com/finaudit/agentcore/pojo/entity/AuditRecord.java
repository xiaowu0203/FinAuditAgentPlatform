package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.AuditAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审批留痕（audit_record，append-only：每次审批动作追加一条，审计溯源）。
 * <p>记录操作人/当时角色/变更前后金额/意见 + 变更前后数据快照（before_data/after_data，JSON）。
 * 快照自包含（含 reimb 顶层字段+明细+附件，不含预签名 URL/OSS 路径），时间线可直接 before→after diff；
 * 首条 SUBMIT 的 before_data 为 NULL（此前无数据）。不更新不删除（仅逻辑删除预留）。</p>
 */
@Getter
@Setter
@TableName(value = "audit_record", autoResultMap = true)
public class AuditRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "留痕ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "工单ID")
    private Long ticketId;

    @Schema(description = "动作: SUBMIT/APPROVE/REJECT/AMEND/TERMINATE/RERUN/RERUN_FAILED/WITHDRAW/WITHDRAW_REQ/WITHDRAW_AGREE/WITHDRAW_REFUSE")
    private String action;

    @Schema(description = "变更前金额")
    private BigDecimal beforeAmount;

    @Schema(description = "变更后金额")
    private BigDecimal afterAmount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "变更前数据快照（首条 SUBMIT 为 NULL；含 reimb 顶层字段+明细+附件）")
    private Map<String, Object> beforeData;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "变更后数据快照（每次动作落一条，审批时点数据现场）")
    private Map<String, Object> afterData;

    @Schema(description = "操作意见")
    private String comment;

    @Schema(description = "操作人用户ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "操作人当时角色（审计溯源）")
    private String operatorRoles;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 通用构造（submitted/approve/reject/amend/terminate 均走此入口；系统动作 operator 三字段传 null）。
     */
    public static AuditRecord of(Long tenantId, Long ticketId, AuditAction action,
                                 BigDecimal beforeAmount, BigDecimal afterAmount, String comment,
                                 Long operatorId, String operatorName, String operatorRoles) {
        return ofSnapshot(tenantId, ticketId, action, beforeAmount, afterAmount, comment,
                operatorId, operatorName, operatorRoles, null, null);
    }

    /**
     * 带快照构造（P3b 工作流重设计后统一入口：每次动作携带变更前后数据快照）。
     *
     * @param beforeData 变更前快照；首条 SUBMIT 传 null（此前无数据）
     * @param afterData  变更后快照
     */
    public static AuditRecord ofSnapshot(Long tenantId, Long ticketId, AuditAction action,
                                         BigDecimal beforeAmount, BigDecimal afterAmount, String comment,
                                         Long operatorId, String operatorName, String operatorRoles,
                                         Map<String, Object> beforeData, Map<String, Object> afterData) {
        AuditRecord record = new AuditRecord();
        record.setTenantId(tenantId);
        record.setTicketId(ticketId);
        record.setAction(action.name());
        record.setBeforeAmount(beforeAmount);
        record.setAfterAmount(afterAmount);
        record.setBeforeData(beforeData);
        record.setAfterData(afterData);
        record.setComment(comment);
        record.setOperatorId(operatorId);
        record.setOperatorName(operatorName);
        record.setOperatorRoles(operatorRoles);
        return record;
    }
}
