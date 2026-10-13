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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 发票标识符投影（invoice_record，P3.8 R2）。
 *
 * <p><b>为什么需要这张表</b>：{@code expense_attachment.ocr_result} 是 JSON，MySQL 5.7 无法对
 * JSON 内部字段建索引，发票代码/号码只能全表扫描 + 应用层解析，无法担当查重主键。
 * 本表把票号投影为普通列，使「同一张票是否已报销」可用唯一索引直接判定。</p>
 *
 * <p><b>唯一键口径</b>：{@code uk(tenant_id, invoice_code, invoice_num, deleted)}。
 * 发票代码/号码缺失时统一归一为 <b>空串而不是 NULL</b>——MySQL 唯一索引不约束 NULL，
 * 若落 NULL 则同一张票可重复投影，唯一键形同虚设。含 {@code deleted} 是为了支持
 * 逻辑删除后重新插入（同 {@code agent_task_step.uk_task_step} 惯例）。</p>
 *
 * <p>写入口收敛在 {@code InvoiceRecordService}（AGENTS.md §5.9）。</p>
 */
@Getter
@Setter
@TableName("invoice_record")
public class InvoiceRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "发票代码（缺失为空串）")
    private String invoiceCode;

    @Schema(description = "发票号码（缺失为空串）")
    private String invoiceNum;

    @Schema(description = "销售方税号")
    private String sellerTaxNo;

    @Schema(description = "归属报销单ID")
    private Long reimbId;

    @Schema(description = "来源附件 file_record.id")
    private Long fileRecordId;

    @Schema(description = "来源 expense_attachment.id")
    private Long attachmentId;

    @Schema(description = "票面金额（价税合计）")
    private BigDecimal amount;

    @Schema(description = "开票日期")
    private LocalDate invDate;

    @Schema(description = "同一张票被识别的次数")
    private Integer seenCount;

    /**
     * 创建时间。由 {@code AuditTimestampMetaObjectHandler} 填充（见 common-mybatisplus-starter）。
     * <p>必须显式标注 {@code fill}，否则 {@code updateById} 会把从库里读出的旧 {@code updated_at}
     * 一并写进 SET，显式赋值会抑制 MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP}。</p>
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 更新时间。由 {@code AuditTimestampMetaObjectHandler} 在 INSERT/UPDATE 时填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由 OCR 归一化结果构造投影行。
     * <p>{@code tenant_id} 由多租户拦截器自动填充，此处不显式赋值。</p>
     *
     * @param invoiceCode  发票代码（已由调用方归一：空白转 null）
     * @param invoiceNum   发票号码（已由调用方归一）
     * @param sellerTaxNo  销售方税号，可空
     * @param reimbId      归属报销单ID，可空（OCR 可能先于绑定完成）
     * @param fileRecordId 来源附件 file_record.id
     * @param attachmentId 来源 expense_attachment.id
     * @param amount       票面金额，可空
     * @param invDate      开票日期，可空
     */
    public static InvoiceRecord from(String invoiceCode, String invoiceNum, String sellerTaxNo,
                                     Long reimbId, Long fileRecordId, Long attachmentId,
                                     BigDecimal amount, LocalDate invDate) {
        InvoiceRecord r = new InvoiceRecord();
        // 归一为空串：唯一键要求确定性取值，NULL 不受唯一索引约束
        r.setInvoiceCode(normalizeKey(invoiceCode));
        r.setInvoiceNum(normalizeKey(invoiceNum));
        r.setSellerTaxNo(sellerTaxNo);
        r.setReimbId(reimbId);
        r.setFileRecordId(fileRecordId);
        r.setAttachmentId(attachmentId);
        r.setAmount(amount);
        r.setInvDate(invDate);
        r.setSeenCount(1);
        return r;
    }

    /** 唯一键分量归一：null/空白 → 空串；其余去除首尾空白 */
    private static String normalizeKey(String v) {
        if (v == null) {
            return "";
        }
        return v.trim();
    }

    /** 该投影是否带有效票号（票号缺失的票据不参与按票查重） */
    public boolean hasInvoiceIdentity() {
        return invoiceNum != null && !invoiceNum.isBlank();
    }

    /**
     * 投影为跨服务契约 VO（P3.8 R3，供 tool-service 的 invoice_match 工具消费）。
     * <p>转换封装在本类而非 common-code：common-code 是公共契约模块，
     * 不得反向依赖 agent-core 实体（依赖倒置）。</p>
     */
    public com.finaudit.starter.web.feign.dto.InvoiceRecordVO toVO() {
        return new com.finaudit.starter.web.feign.dto.InvoiceRecordVO(
                invoiceCode, invoiceNum, sellerTaxNo, amount,
                invDate == null ? null : invDate.toString(), reimbId);
    }

    /**
     * 同一张票再次被识别（重复上传同一发票，或重跑时再次 OCR）。
     * <p>累加 {@code seen_count} 并把来源附件更新为最新一次，便于追溯
     * 「同一张票出现在哪几张附件里」。{@code reimb_id} 非空时同步刷新，
     * 使票与最终归属单据保持一致。</p>
     */
    public void applySeenAgain(Long attachmentId, Long fileRecordId, Long reimbId) {
        this.seenCount = (this.seenCount == null ? 1 : this.seenCount) + 1;
        if (attachmentId != null) {
            this.attachmentId = attachmentId;
        }
        if (fileRecordId != null) {
            this.fileRecordId = fileRecordId;
        }
        if (reimbId != null) {
            this.reimbId = reimbId;
        }
    }
}
