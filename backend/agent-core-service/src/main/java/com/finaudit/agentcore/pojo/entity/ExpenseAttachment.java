package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.AttachmentFileType;
import com.finaudit.agentcore.enums.OcrStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 报销业务附件（expense_attachment）。
 * <p>仅存 file_record 引用 + 业务字段（fileType/ocrStatus/ocrResult），文件元数据在 file-service 的 file_record；
 * reimb_id 在提交绑定时回填。</p>
 */
@Getter
@Setter
@TableName(value = "expense_attachment", autoResultMap = true)
public class ExpenseAttachment {

    @TableId(type = IdType.AUTO)
    @Schema(description = "业务附件ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "报销单ID（提交绑定时回填，未关联为空）")
    private Long reimbId;

    @Schema(description = "file_record.id（文件元数据在 file-service）")
    private Long fileRecordId;

    @Schema(description = "附件类型（P2a 默认 OTHER，分类归 P2b OCR 产生）")
    private String fileType;

    @Schema(description = "OCR状态（PENDING/SUCCESS/FAILED）")
    private String ocrStatus;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "OCR抽取结果（JSON）")
    private Map<String, Object> ocrResult;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由提交绑定的文件构造业务附件（fileType 默认 OTHER，ocr_status=PENDING）。
     */
    public static ExpenseAttachment from(Long fileRecordId, Long reimbId, Long tenantId) {
        ExpenseAttachment attachment = new ExpenseAttachment();
        attachment.setTenantId(tenantId);
        attachment.setReimbId(reimbId);
        attachment.setFileRecordId(fileRecordId);
        attachment.setFileType(AttachmentFileType.OTHER.name());
        attachment.setOcrStatus(OcrStatus.PENDING.name());
        return attachment;
    }

    /**
     * 仅用于回填 reimb_id 的批量 UPDATE 载体（转换封装在实体类，业务层不手写 set 组装，见 CLAUDE.md §5.6）。
     */
    public static ExpenseAttachment forBindReimb(Long reimbId) {
        ExpenseAttachment attachment = new ExpenseAttachment();
        attachment.setReimbId(reimbId);
        return attachment;
    }

    /**
     * 回填 OCR 结果（P2b ocr_extract 工具写回，更新型 apply 见 CLAUDE.md §5.6）。
     * fileType 空值保留原值（分类未产生时不覆盖）。
     */
    public void applyOcrResult(String ocrStatus, String fileType, Map<String, Object> ocrResult) {
        this.ocrStatus = ocrStatus;
        if (fileType != null && !fileType.isBlank()) {
            this.fileType = fileType;
        }
        this.ocrResult = ocrResult;
    }
}
