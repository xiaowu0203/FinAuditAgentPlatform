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

import java.time.LocalDateTime;

/**
 * 发票—报销单关联（invoice_reimb_link，P3.8 R3 修复）。
 *
 * <p><b>为什么需要这张表（R3 联调发现的架构缺陷）</b>：{@code invoice_record} 每张票只有一行、
 * 只带<b>一个</b> {@code reimb_id}，因此「同一张票被多张报销单共用」这个事实<b>在表里存不下</b>——
 * 而按票查重的判定恰恰要回答「这张票还属于哪张单」。实测：同一张票提交 5 次后，
 * 投影行的 {@code reimb_id} 只记录了最后一次（=57），于是 53/54/55/56 各单查询时，
 * 都把自己排除掉了（行进/出都被当成「属于自己」），硬命中<b>恒不触发</b>。</p>
 *
 * <p>{@code invoice_record.reimb_id} 保留为「最近一次归属」，仅用于展示；
 * <b>归属关系的权威数据在本表</b>（一对多：一票多单）。</p>
 */
@Getter
@Setter
@TableName("invoice_reimb_link")
public class InvoiceReimbLink {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "发票投影ID（invoice_record.id）")
    private Long invoiceRecordId;

    @Schema(description = "报销单ID")
    private Long reimbId;

    @Schema(description = "来源附件 file_record.id（最近一次）")
    private Long fileRecordId;

    @Schema(description = "同一张票在本单内被识别的次数")
    private Integer seenCount;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 建立「发票 → 报销单」归属。
     * <p>{@code tenant_id} 由多租户拦截器自动填充。</p>
     */
    public static InvoiceReimbLink of(Long invoiceRecordId, Long reimbId, Long fileRecordId) {
        InvoiceReimbLink link = new InvoiceReimbLink();
        link.setInvoiceRecordId(invoiceRecordId);
        link.setReimbId(reimbId);
        link.setFileRecordId(fileRecordId);
        link.setSeenCount(1);
        return link;
    }

    /** 同一张票在本单内再次被识别：累加次数并刷新来源附件 */
    public void applySeenAgain(Long fileRecordId) {
        this.seenCount = (this.seenCount == null ? 1 : this.seenCount) + 1;
        if (fileRecordId != null) {
            this.fileRecordId = fileRecordId;
        }
    }
}
