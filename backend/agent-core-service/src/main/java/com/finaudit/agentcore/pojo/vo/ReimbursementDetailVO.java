package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 报销单详情响应（基础信息 + 明细 + 附件预签名 URL）。
 */
@Data
public class ReimbursementDetailVO {

    @Schema(description = "报销单基础信息")
    private ReimbursementVO reimbursement;

    @Schema(description = "报销明细")
    private List<ReimbursementItemVO> items;

    @Schema(description = "附件（含预签名 URL）")
    private List<AttachmentVO> attachments;

    public static ReimbursementDetailVO from(ExpenseReimbursement r, List<ReimbursementItemVO> items,
                                             List<AttachmentVO> attachments) {
        ReimbursementDetailVO vo = new ReimbursementDetailVO();
        vo.setReimbursement(ReimbursementVO.from(r));
        vo.setItems(items);
        vo.setAttachments(attachments);
        return vo;
    }
}
