package com.finaudit.file.pojo.vo;

import com.finaudit.file.pojo.entity.FileRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件元数据响应（含预签名访问 URL）。
 */
@Data
public class FileVO {

    @Schema(description = "文件ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "对象存储 key")
    private String objectName;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "字节大小")
    private Long size;

    @Schema(description = "预签名访问 URL（默认有效期，经浏览器内联渲染）")
    private String url;

    public static FileVO from(FileRecord record, String url) {
        FileVO vo = new FileVO();
        vo.setId(record.getId());
        vo.setTenantId(record.getTenantId());
        vo.setFileName(record.getFileName());
        vo.setObjectName(record.getObjectName());
        vo.setContentType(record.getContentType());
        vo.setSize(record.getSize());
        vo.setUrl(url);
        return vo;
    }
}
