package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.ExpenseAttachment;
import com.finaudit.starter.web.feign.dto.FileRecordVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 报销附件响应（业务字段 + 经 file-service 联取的元数据 + 预签名 URL）。
 */
@Data
public class AttachmentVO {

    @Schema(description = "业务附件ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "报销单ID")
    private Long reimbId;

    @Schema(description = "file_record.id")
    private Long fileRecordId;

    @Schema(description = "原始文件名（file-service 元数据）")
    private String fileName;

    @Schema(description = "对象存储 key（file-service 元数据）")
    private String objectName;

    @Schema(description = "附件类型")
    private String fileType;

    @Schema(description = "OCR状态")
    private String ocrStatus;

    @Schema(description = "OCR 抽取字段（JSON；P3b 工单详情对照展示，无 OCR 为 null）")
    private Map<String, Object> ocrResult;

    @Schema(description = "预签名预览 URL（默认有效期）")
    private String url;

    /**
     * 由业务附件 + file-service 元数据构造（file 缺失时 fileName/objectName 置空，不阻塞详情）。
     */
    public static AttachmentVO from(ExpenseAttachment a, FileRecordVO file, String url) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(a.getId());
        vo.setTenantId(a.getTenantId());
        vo.setReimbId(a.getReimbId());
        vo.setFileRecordId(a.getFileRecordId());
        vo.setFileType(a.getFileType());
        vo.setOcrStatus(a.getOcrStatus());
        vo.setOcrResult(a.getOcrResult());
        vo.setFileName(file == null ? null : file.fileName());
        vo.setObjectName(file == null ? null : file.objectName());
        vo.setUrl(url);
        return vo;
    }
}
