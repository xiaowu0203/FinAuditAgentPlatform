package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报销单响应（列表 / 提交返回）。
 */
@Data
public class ReimbursementVO {

    @Schema(description = "报销单ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "报销单号")
    private String reimbNo;

    @Schema(description = "报销标题")
    private String title;

    @Schema(description = "费用类型")
    private String expenseType;

    @Schema(description = "申请人用户ID")
    private Long applicantId;

    @Schema(description = "部门")
    private String deptName;

    @Schema(description = "申报总金额")
    private BigDecimal totalAmount;

    @Schema(description = "关联任务ID（提交后反写）")
    private Long taskId;

    @Schema(description = "审核状态")
    private String status;

    @Schema(description = "报销日期")
    private LocalDate claimDate;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static ReimbursementVO from(ExpenseReimbursement r) {
        ReimbursementVO vo = new ReimbursementVO();
        vo.setId(r.getId());
        vo.setTenantId(r.getTenantId());
        vo.setReimbNo(r.getReimbNo());
        vo.setTitle(r.getTitle());
        vo.setExpenseType(r.getExpenseType());
        vo.setApplicantId(r.getApplicantId());
        vo.setDeptName(r.getDeptName());
        vo.setTotalAmount(r.getTotalAmount());
        vo.setTaskId(r.getTaskId());
        vo.setStatus(r.getStatus());
        vo.setClaimDate(r.getClaimDate());
        vo.setRemark(r.getRemark());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }
}
