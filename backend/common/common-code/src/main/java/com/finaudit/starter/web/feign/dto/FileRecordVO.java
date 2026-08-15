package com.finaudit.starter.web.feign.dto;

/**
 * 文件元数据（跨服务 Feign 契约 DTO：file-service 提供，消费方投影 FileVO 所需字段）。
 * <p>业务侧经 {@link com.finaudit.starter.web.feign.FileServiceFeign} 获取，
 * 仅消费元数据与经预签名 URL 读文件，不接触 OSS 直连。</p>
 *
 * @param id          file_record id（业务附件表 file_record_id 引用它）
 * @param fileName    原始文件名
 * @param objectName  对象 key（租户前缀 {tenantId}/{yyyyMM}/{uuid}{ext}）
 * @param contentType MIME 类型
 * @param size        字节大小
 */
public record FileRecordVO(Long id, String fileName, String objectName, String contentType, Long size) {
}
