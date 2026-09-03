package com.finaudit.file.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文件元数据（file_record）。
 * <p>纯二进制资源元数据，无任何财务业务字段：文件本体在对象存储（MinIO），
 * object_name 为对象 key（含租户前缀，防跨租户碰撞）；业务附件关联经 file_record_id 引用本表。
 * 本服务不感知审核流程，不建 Agent 任务。</p>
 */
@Getter
@Setter
@TableName(value = "file_record", autoResultMap = true)
public class FileRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "文件ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "上传人ID（P3.5c 归属校验：直接预览/下载须本人或财务；内部 Feign 无上下文放行）")
    private Long createdBy;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "对象存储 key（含租户前缀 {tenantId}/{yyyyMM}/{uuid}{ext}）")
    private String objectName;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "字节大小")
    private Long size;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由上传结果构造文件元数据（转换封装在实体类，业务层不手写 set 组装，见 CLAUDE.md §5.6）。
     */
    public static FileRecord from(String fileName, String objectName, String contentType, Long size, Long tenantId, Long createdBy) {
        FileRecord record = new FileRecord();
        record.setTenantId(tenantId);
        record.setCreatedBy(createdBy);
        record.setFileName(fileName);
        record.setObjectName(objectName);
        record.setContentType(contentType);
        record.setSize(size);
        return record;
    }
}
