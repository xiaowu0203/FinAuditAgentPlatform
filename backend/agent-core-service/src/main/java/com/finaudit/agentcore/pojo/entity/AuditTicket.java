package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.AuditTicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批工单（audit_ticket，P3b 人机协同审批闭环）。
 * <p>流水线判定 NEED_REVIEW 时生成；状态机见 {@link AuditTicketStatus}。
 * review_reasons 为复核原因列表（JSON，仅存储）；rerun_count 上限 3（P3 §9 防死循环）；
 * audit_level 恒 1（预留多级审批 TODO P5+）。</p>
 */
@Getter
@Setter
@TableName(value = "audit_ticket", autoResultMap = true)
public class AuditTicket {

    @TableId(type = IdType.AUTO)
    @Schema(description = "工单ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "关联任务ID")
    private Long taskId;

    @Schema(description = "工单编号（AT-{taskNo}）")
    private String ticketNo;

    @Schema(description = "任务标题（冗余展示）")
    private String title;

    @Schema(description = "触发类型: OVER_LIMIT / RULE_FAIL / RISK_HIT")
    private String triggerType;

    @Schema(description = "复核原因描述（review_reasons join 截断）")
    private String riskDesc;

    @Schema(description = "触发步骤（预留，暂置 NULL）")
    private Integer stepNo;

    @Schema(description = "申报总额（任务入参 claimedTotal）")
    private BigDecimal originAmount;

    @Schema(description = "修正后总额（提交人修改重跑时写，重跑后为最终金额）")
    private BigDecimal adjustedAmount;

    @Schema(description = "工单状态（PENDING / APPROVED / REJECTED / AMENDED / TERMINATED / WITHDRAW_PENDING / WITHDRAWN）")
    private String status;

    @Schema(description = "审批级数（P3 恒 1，预留多级审批 TODO P5+）")
    private Integer auditLevel;

    @Schema(description = "修改重跑次数（admin amend 与提交人 resubmit 共用），上限 3")
    private Integer rerunCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "复核原因列表（JSON）")
    private List<String> reviewReasons;

    @Schema(description = "最近处理人用户ID")
    private Long auditorId;

    @Schema(description = "最近处理意见")
    private String auditComment;

    @Schema(description = "申请人用户ID（任务提交人）")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由流水线 NEED_REVIEW 判定构造工单（初始状态 PENDING，auditLevel=1，rerunCount=0）。
     */
    public static AuditTicket from(Long tenantId, Long taskId, String ticketNo, String title,
                                   String triggerType, String riskDesc, BigDecimal originAmount,
                                   List<String> reviewReasons, Long createdBy) {
        AuditTicket ticket = new AuditTicket();
        ticket.setTenantId(tenantId);
        ticket.setTaskId(taskId);
        ticket.setTicketNo(ticketNo);
        ticket.setTitle(title);
        ticket.setTriggerType(triggerType);
        ticket.setRiskDesc(riskDesc);
        ticket.setOriginAmount(originAmount);
        ticket.setStatus(AuditTicketStatus.PENDING.name());
        ticket.setAuditLevel(1);
        ticket.setRerunCount(0);
        ticket.setReviewReasons(reviewReasons);
        ticket.setCreatedBy(createdBy);
        return ticket;
    }

    /**
     * 终局动作回写（approve / reject / terminate）：置状态 + 处理人与意见。
     */
    public void applyAudit(AuditTicketStatus status, Long auditorId, String auditComment) {
        this.status = status.name();
        this.auditorId = auditorId;
        this.auditComment = auditComment;
    }

    /**
     * 财务 amend 回写（保留：旧接口，语义同提交人 resubmit；写处理人+意见+重跑次数自增）。
     *
     * @deprecated P3b 重设计后由提交人 {@link #applySubmitterAmend(BigDecimal, String)} 承担修改重跑；
     *             财务 amend 入口将移除。此处仅保兼容，勿新增调用。
     */
    @Deprecated
    public void applyAmend(BigDecimal adjustedAmount, Long auditorId, String auditComment) {
        this.status = AuditTicketStatus.AMENDED.name();
        this.adjustedAmount = adjustedAmount;
        this.auditorId = auditorId;
        this.auditComment = auditComment;
        this.rerunCount = (this.rerunCount == null ? 0 : this.rerunCount) + 1;
    }

    /**
     * 提交人 resubmit 回写：置 AMENDED + 修正后金额 + 意见 + 重跑次数自增。
     * <b>不动 auditorId</b>（处理人仍为最近一次财务动作，防止把申请人的 ID 写成审批人）。
     */
    public void applySubmitterAmend(BigDecimal adjustedAmount, String auditComment) {
        this.status = AuditTicketStatus.AMENDED.name();
        this.adjustedAmount = adjustedAmount;
        this.auditComment = auditComment;
        this.rerunCount = (this.rerunCount == null ? 0 : this.rerunCount) + 1;
    }

    /**
     * 重跑复位（仅改状态，保留原复核原因）：用于重跑失败复位（onRerunFail，本次未产生新原因，
     * 原原因仍有效）。重跑再次命中 NEED_REVIEW 应用 {@link #applyRerunResetWith}（刷新原因）。
     */
    public void applyRerunReset() {
        this.status = AuditTicketStatus.PENDING.name();
    }

    /**
     * 重跑再次命中 NEED_REVIEW：工单复位 PENDING，并刷新复核原因/触发类型/风险描述
     * （上次与本次命中原因可能不同，如 OVER_LIMIT → RISK_HIT，必须覆盖而非保留旧值）。
     */
    public void applyRerunResetWith(List<String> reviewReasons, String triggerType, String riskDesc) {
        this.status = AuditTicketStatus.PENDING.name();
        this.reviewReasons = reviewReasons;
        this.triggerType = triggerType;
        this.riskDesc = riskDesc;
    }

    /**
     * 重跑自动通过：工单闭合为 APPROVED（审计意见由系统写入）。
     */
    public void applyAutoPass(String auditComment) {
        this.status = AuditTicketStatus.APPROVED.name();
        this.auditComment = auditComment;
    }

    /**
     * 提交人撤回（PENDING → WITHDRAWN，直接生效；operator 记撤回人）。
     */
    public void applyWithdraw(Long operatorId, String auditComment) {
        this.status = AuditTicketStatus.WITHDRAWN.name();
        this.auditComment = auditComment;
        this.auditorId = operatorId;
    }

    /**
     * 提交人发起撤销申请（APPROVED → WITHDRAW_PENDING，等财务同意/拒绝）。
     */
    public void applyWithdrawRequest(String auditComment) {
        this.status = AuditTicketStatus.WITHDRAW_PENDING.name();
        this.auditComment = auditComment;
    }

    /**
     * 财务拒绝撤销（WITHDRAW_PENDING → APPROVED 原地返回）。
     */
    public void applyWithdrawRefuse(String auditComment) {
        this.status = AuditTicketStatus.APPROVED.name();
        this.auditComment = auditComment;
    }
}
