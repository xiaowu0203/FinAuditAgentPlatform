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
 * <p>业务服务读取附件一律经本契约远程调 file-service（禁止直连 OSS）：上传仅前端对接 file-service；
 * 业务侧只拿元数据与预签名 URL。租户经 {@code X-Tenant-Id} 请求头传递，服务间经 Nacos 服务名直连（不经网关）。</p>
 *
 * <p><b>P3.8 / R0-3：本契约指向内部端点 {@code /internal/files/**}，不再复用对外端点</b>。
 * 原因：{@code FeignHeaderPropagator} 会在业务服务（agent-core）的 HTTP 请求线程上透传调用者身份头，
 * 若走对外端点，file-service 的用户可见性校验会把「内部代读他人附件」判为越权而拒绝，导致
 * 报销单附件区静默空白。内部端点仅做租户隔离，语义与「服务间数据获取」一致。</p>
 *
 * <p>安全边界：{@code /internal/**} 不在网关路由表内（网关只路由 {@code /api/v1/**}），外部不可达；
 * 越权由调用方负责——agent-core 提交时校验附件归属租户，tool-service 由 {@code ToolAccessGuard} 校验单据归属。</p>
 *
 * <p>⚠️ 与 file-service 的 {@code InternalFileController} 成对修改</p>
 */
@FeignClient(name = "file-service")
public interface FileServiceFeign {

    /**
     * 附件详情（单条，内部）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param id       file_record id
     * @return 文件元数据（消费方投影）
     */
    @GetMapping("/internal/files/{id}")
    R<FileRecordVO> getFile(@RequestHeader("X-Tenant-Id") Long tenantId, @PathVariable("id") Long id);

    /**
     * 附件预览预签名 URL（内部，按对象 content-type 浏览器内联渲染）。
     * <p>供 agent-core 组装报销单附件展示（{@code AttachmentService}）。</p>
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param id       file_record id
     * @return 预签名 URL（默认有效期）
     */
    @GetMapping("/internal/files/{id}/preview")
    R<String> presignPreview(@RequestHeader("X-Tenant-Id") Long tenantId, @PathVariable("id") Long id);

    /**
     * 附件详情（批量，内部；仅租户隔离，校验存在且归属租户）。
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param ids      file_record id 列表（逗号分隔，如 {@code ids=1,2}）
     * @return 文件元数据列表（消费方投影）
     */
    @GetMapping("/internal/files")
    R<List<FileRecordVO>> getFiles(@RequestHeader("X-Tenant-Id") Long tenantId,
                                   @RequestParam("ids") List<Long> ids);

    /**
     * 文件下载预签名 URL（内部，带 {@code Content-Disposition: attachment}）。
     * <p>供 tool-service 取票据图片字节做 OCR（{@code OcrExtractTool}）。</p>
     *
     * @param tenantId 租户ID（经 X-Tenant-Id 请求头传递）
     * @param id       file_record id
     * @return 预签名 URL（默认有效期）
     */
    @GetMapping("/internal/files/{id}/download")
    R<String> presignDownload(@RequestHeader("X-Tenant-Id") Long tenantId, @PathVariable("id") Long id);
}
