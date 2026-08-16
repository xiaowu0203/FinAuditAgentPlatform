package com.finaudit.starter.web.feign;

import com.finaudit.starter.web.feign.dto.FileRecordVO;
import com.finaudit.starter.web.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * file-service 文件契约（跨服务 Feign 客户端，统一放 common-code 供各消费方复用）。
 * <p>业务服务读取附件一律经本契约远程调 file-service（禁止直连 OSS）：
 * 上传仅前端对接 file-service；业务侧只拿元数据与预签名 URL。
 * 租户经 {@code X-Tenant-Id} 请求头传递，服务间经 Nacos 服务名直连（不经网关）；
 * 如需请求头/token 透传能力，消费方需引入 common-feign-starter。</p>
 */
@FeignClient(name = "file-service")
public interface FileServiceFeign {

    /**
     * 附件详情（单条）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param id       file_record id
     * @return 文件元数据（消费方投影）
     */
    @GetMapping("/api/v1/files/{id}")
    R<FileRecordVO> getFile(@RequestHeader("X-Tenant-Id") Long tenantId, @PathVariable("id") Long id);

    /**
     * 附件详情（批量，校验存在且归属租户）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param ids      file_record id 列表（逗号分隔，如 {@code ids=1,2}）
     * @return 文件元数据列表（消费方投影）
     */
    @GetMapping("/api/v1/files")
    R<List<FileRecordVO>> getFiles(@RequestHeader("X-Tenant-Id") Long tenantId,
                                   @RequestParam("ids") List<Long> ids);

    /**
     * 文件预览预签名 URL（按对象 content-type 浏览器内联渲染）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param id       file_record id
     * @return 预签名 URL（默认有效期）
     */
    @GetMapping("/api/v1/files/{id}/preview")
    R<String> presignPreview(@RequestHeader("X-Tenant-Id") Long tenantId, @PathVariable("id") Long id);

    /**
     * 文件下载预签名 URL（响应头带 {@code Content-Disposition: attachment; filename="..."}）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param id       file_record id
     * @return 预签名 URL（默认有效期）
     */
    @GetMapping("/api/v1/files/{id}/download")
    R<String> presignDownload(@RequestHeader("X-Tenant-Id") Long tenantId, @PathVariable("id") Long id);
}
