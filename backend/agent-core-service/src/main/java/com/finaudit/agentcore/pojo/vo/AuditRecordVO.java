package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.AuditRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审批留痕响应（工单详情留痕时间线）。
 */
@Data
public class AuditRecordVO {

    @Schema(description = "留痕ID")
    private Long id;

    @Schema(description = "动作: SUBMIT/APPROVE/REJECT/AMEND/TERMINATE/RERUN")
    private String action;

    @Schema(description = "变更前金额")
    private BigDecimal beforeAmount;

    @Schema(description = "变更后金额")
    private BigDecimal afterAmount;

    @Schema(description = "操作意见")
    private String comment;

    @Schema(description = "操作人用户ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "操作人当时角色")
    private String operatorRoles;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;

    public static AuditRecordVO from(AuditRecord r) {
        AuditRecordVO vo = new AuditRecordVO();
        vo.setId(r.getId());
        vo.setAction(r.getAction());
        vo.setBeforeAmount(r.getBeforeAmount());
        vo.setAfterAmount(r.getAfterAmount());
        vo.setComment(r.getComment());
        vo.setOperatorId(r.getOperatorId());
        vo.setOperatorName(r.getOperatorName());
        vo.setOperatorRoles(r.getOperatorRoles());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }
}
