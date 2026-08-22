package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.AuditTicket;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批工单响应（列表 / 详情中的工单基础信息）。
 */
@Data
public class AuditTicketVO {

    @Schema(description = "工单ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "关联任务ID")
    private Long taskId;

    @Schema(description = "工单编号（AT-{taskNo}）")
    private String ticketNo;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "触发类型: OVER_LIMIT / RULE_FAIL / RISK_HIT")
    private String triggerType;

    @Schema(description = "复核原因描述")
    private String riskDesc;

    @Schema(description = "申报总额")
    private BigDecimal originAmount;

    @Schema(description = "财务修改后总额（amend 后为最新）")
    private BigDecimal adjustedAmount;

    @Schema(description = "工单状态（PENDING / APPROVED / REJECTED / AMENDED / TERMINATED）")
    private String status;

    @Schema(description = "重跑次数")
    private Integer rerunCount;

    @Schema(description = "复核原因列表")
    private List<String> reviewReasons;

    @Schema(description = "最近处理人用户ID")
    private Long auditorId;

    @Schema(description = "最近处理意见")
    private String auditComment;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static AuditTicketVO from(AuditTicket t) {
        AuditTicketVO vo = new AuditTicketVO();
        vo.setId(t.getId());
        vo.setTenantId(t.getTenantId());
        vo.setTaskId(t.getTaskId());
        vo.setTicketNo(t.getTicketNo());
        vo.setTitle(t.getTitle());
        vo.setTriggerType(t.getTriggerType());
        vo.setRiskDesc(t.getRiskDesc());
        vo.setOriginAmount(t.getOriginAmount());
        vo.setAdjustedAmount(t.getAdjustedAmount());
        vo.setStatus(t.getStatus());
        vo.setRerunCount(t.getRerunCount());
        vo.setReviewReasons(t.getReviewReasons());
        vo.setAuditorId(t.getAuditorId());
        vo.setAuditComment(t.getAuditComment());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }
}
