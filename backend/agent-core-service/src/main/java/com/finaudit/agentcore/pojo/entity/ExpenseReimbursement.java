package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.ReimbursementStatus;
import com.finaudit.agentcore.pojo.dto.ReimbursementItemRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementSubmitRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 报销单（expense_reimbursement，审核流程归属 agent-core）。
 * <p>total_amount 为服务端按明细求和（Decimal 强制）；task_id 提交后服务内直调反写；
 * items 为明细 JSON（仅存储，不参与 WHERE 过滤，MySQL 5.7 限制）。</p>
 */
@Getter
@Setter
@TableName(value = "expense_reimbursement", autoResultMap = true)
public class ExpenseReimbursement {

    /** 报销单号时间格式 */
    private static final DateTimeFormatter REIMB_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @TableId(type = IdType.AUTO)
    @Schema(description = "报销单ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "报销单号")
    private String reimbNo;

    @Schema(description = "报销标题")
    private String title;

    @Schema(description = "费用类型（TRAVEL/ENTERTAINMENT/OFFICE）")
    private String expenseType;

    @Schema(description = "申请人用户ID")
    private Long applicantId;

    @Schema(description = "部门")
    private String deptName;

    @Schema(description = "部门ID（P3.5b 权威关联键；deptName 为提交时快照）")
    private Long deptId;

    @Schema(description = "申报总金额")
    private BigDecimal totalAmount;

    @Schema(description = "关联 agent_task.id（提交后反写）")
    private Long taskId;

    @Schema(description = "审核状态（对齐任务状态机）")
    private String status;

    @Schema(description = "报销日期")
    private LocalDate claimDate;

    @Schema(description = "备注")
    private String remark;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "报销明细 JSON")
    private List<Map<String, Object>> items;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由提交请求构造报销单（初始状态 PENDING，自动生成报销单号，total_amount 服务端计算）。
     */
    public static ExpenseReimbursement from(ReimbursementSubmitRequest request, Long tenantId,
                                            Long applicantId, BigDecimal totalAmount) {
        ExpenseReimbursement reimb = new ExpenseReimbursement();
        reimb.setTenantId(tenantId);
        reimb.setReimbNo(generateReimbNo());
        reimb.setTitle(request.title());
        reimb.setExpenseType(request.expenseType());
        reimb.setApplicantId(applicantId);
        reimb.setDeptName(request.deptName());
        reimb.setDeptId(request.deptId());
        reimb.setTotalAmount(totalAmount);
        reimb.setStatus(ReimbursementStatus.PENDING.name());
        reimb.setClaimDate(request.claimDate());
        reimb.setRemark(request.remark());
        reimb.setItems(itemsToMaps(request.items()));
        return reimb;
    }

    /**
     * 明细请求 → JSON Map（date 转字符串；手写映射避免裸 ObjectMapper 缺 JavaTimeModule 序列化 LocalDate 报错，
     * 也保证 JacksonTypeHandler 读回为稳定字符串）。跨包被 ReimbursementService 复用组任务快照，故为 public。
     */
    public static List<Map<String, Object>> itemsToMaps(List<ReimbursementItemRequest> items) {
        List<Map<String, Object>> maps = new ArrayList<>(items.size());
        for (ReimbursementItemRequest item : items) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", item.name());
            map.put("amount", item.amount());
            map.put("amountType", item.amountType());
            map.put("quantity", item.quantity());
            map.put("unitPrice", item.unitPrice());
            map.put("date", item.date() == null ? null : item.date().toString());
            // P2c 差旅/补贴评估字段（可空，任务快照 items 同步携带）
            map.put("city", item.city());
            map.put("hotelDays", item.hotelDays());
            map.put("hotelAmount", item.hotelAmount());
            map.put("transportAmount", item.transportAmount());
            map.put("subsidyAmount", item.subsidyAmount());
            maps.add(map);
        }
        return maps;
    }

    /**
     * 报销单号：R + yyyyMMddHHmmss + 4 位随机数（包私有，供单测）。
     */
    static String generateReimbNo() {
        return "R" + LocalDateTime.now().format(REIMB_NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /**
     * 反写关联任务 ID（提交成功后调用）。
     */
    public void applyTaskId(Long taskId) {
        this.taskId = taskId;
    }
}
