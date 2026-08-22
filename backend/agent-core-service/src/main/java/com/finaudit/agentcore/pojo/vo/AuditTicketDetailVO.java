package com.finaudit.agentcore.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 审批工单详情响应（工单 + 关联报销单 + 留痕时间线）。
 */
@Data
public class AuditTicketDetailVO {

    @Schema(description = "工单信息")
    private AuditTicketVO ticket;

    @Schema(description = "关联报销单详情（含明细 + 附件 + OCR；GENERIC 任务无报销单时为 null）")
    private ReimbursementDetailVO reimbursement;

    @Schema(description = "审批留痕（按时间升序）")
    private List<AuditRecordVO> records;

    public static AuditTicketDetailVO from(AuditTicketVO ticket, ReimbursementDetailVO reimbursement,
                                           List<AuditRecordVO> records) {
        AuditTicketDetailVO vo = new AuditTicketDetailVO();
        vo.setTicket(ticket);
        vo.setReimbursement(reimbursement);
        vo.setRecords(records);
        return vo;
    }
}
