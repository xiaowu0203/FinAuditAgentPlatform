package com.finaudit.file.controller;

import com.finaudit.file.pojo.vo.FileVO;
import com.finaudit.file.service.FileService;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文件服务<b>内部契约</b>（服务间 Feign 调用专用，P3.8 / R0-3）。
 *
 * <p><b>为什么必须与 {@link FileController} 分离</b>：原先业务服务复用对外端点
 * {@code /api/v1/files/**} 读取附件，而 {@code FeignHeaderPropagator} 会在业务服务的 HTTP 请求线程上
 * 透传调用者身份头，导致 file-service 的用户可见性校验（{@code requireReadable}）把「内部代读」当成
 * 「用户越权」拒绝 → agent-core 组附件快照失败 → 前端附件区静默空白。
 * 根因是<b>一份契约同时承担两种语义</b>（用户可见性 vs 服务间数据获取），故拆为两个入口。</p>
 *
 * <p><b>安全边界</b>：前缀 {@code /internal/**} <b>不在网关路由表内</b>（{@code agent-gateway}
 * 只路由 {@code /api/v1/**}），因此外部请求经网关不可达；本前缀即内部信任边界，
 * 与对外端点分离是<b>结构性</b>保障，而非依赖请求头声明（内部头可被伪造，不可作为安全依据）。
 * 租户隔离仍由 {@code TenantIdFilter} + MyBatis-Plus 多租户拦截器强制：跨租户 id 查不到数据。
 * 业务级越权由调用方负责：agent-core 提交时校验附件归属，tool-service 由 {@code ToolAccessGuard} 校验。</p>
 *
 * <p>⚠️ 修改本类端点时必须同步 {@code common-code} 的 {@code FileServiceFeign}</p>
 */
@Tag(name = "文件-内部契约", description = "服务间调用（/internal/**, 网关不暴露）")
@RestController
@RequestMapping("/internal/files")
public class InternalFileController {

    private final FileService fileService;

    public InternalFileController(FileService fileService) {
        this.fileService = fileService;
    }

    @Operation(summary = "文件元数据（单条，内部）", description = "仅租户隔离，不做用户可见性校验")
    @GetMapping("/{id}")
    public R<FileVO> detail(@PathVariable Long id,
                            @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(fileService.toVO(fileService.getRequiredForInternal(id)));
    }

    @Operation(summary = "文件批量元数据（内部）", description = "供业务服务组附件快照；仅租户隔离，不做用户可见性校验")
    @GetMapping
    public R<List<FileVO>> batchDetail(@RequestParam("ids") List<Long> ids,
                                      @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(fileService.listByIdsForInternal(ids).stream().map(fileService::toVO).toList());
    }

    @Operation(summary = "预览预签名 URL（内部）", description = "供业务服务组装附件展示；仅租户隔离")
    @GetMapping("/{id}/preview")
    public R<String> preview(@PathVariable Long id,
                             @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(fileService.presignPreviewForInternal(id));
    }

    @Operation(summary = "下载预签名 URL（内部）", description = "供 tool-service 取票据图片做 OCR；仅租户隔离")
    @GetMapping("/{id}/download")
    public R<String> download(@PathVariable Long id,
                              @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId) {
        requireTenant(tenantId);
        return R.success(fileService.presignDownloadForInternal(id));
    }

    /** 租户上下文缺失即拒绝（fail-closed），不以默认租户兜底，避免内部调用串租户。 */
    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，内部契约不接受无租户调用");
        }
    }
}
