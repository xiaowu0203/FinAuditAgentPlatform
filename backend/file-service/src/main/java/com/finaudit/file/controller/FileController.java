package com.finaudit.file.controller;

import com.finaudit.file.pojo.entity.FileRecord;
import com.finaudit.file.pojo.vo.FileVO;
import com.finaudit.file.service.FileService;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接口（纯二进制资源：上传 / 详情 / 预览 / 下载）。
 * <p><b>对用户侧契约</b>：上传与读取均由前端经网关（9080）调用，读接口带用户可见性校验
 * （上传人本人或持有 reimb/audit:viewAll）。</p>
 * <p><b>业务服务读文件一律走内部契约</b> {@link InternalFileController}（{@code /internal/files/**}，
 * 网关不暴露），不再复用本类端点——P3.8 / R0-3 修复了「内部代读被用户可见性校验拒绝导致附件区静默空白」。</p>
 */
@Tag(name = "文件", description = "上传 / 详情 / 预览 / 下载")
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @Operation(summary = "上传文件（multipart → 对象存储）")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<FileVO> upload(@RequestParam("file") MultipartFile file,
                            @RequestHeader(name = "X-Tenant-Id", required = false) Long tenantId,
                            @RequestHeader(name = "X-User-Id", required = false) Long userId) {
        // 租户缺失即拒绝（fail-closed）：此前 defaultValue="1" 会在绕过网关直连时静默落入租户 1
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
        return R.success(fileService.upload(file, tenantId, userId));
    }

    @Operation(summary = "文件详情（含预签名预览 URL）", description = "上传人本人或持有 reimb/audit:viewAll")
    @GetMapping("/{id}")
    public R<FileVO> detail(@PathVariable Long id) {
        FileRecord record = fileService.getRequired(id);
        return R.success(fileService.toVO(record));
    }

    @Operation(summary = "预览预签名 URL（浏览器内联渲染）", description = "上传人本人或持有 reimb/audit:viewAll")
    @GetMapping("/{id}/preview")
    public R<String> preview(@PathVariable Long id) {
        return R.success(fileService.presignPreview(id));
    }

    @Operation(summary = "下载预签名 URL（响应头带 Content-Disposition: attachment）", description = "上传人本人或持有 reimb/audit:viewAll")
    @GetMapping("/{id}/download")
    public R<String> download(@PathVariable Long id) {
        return R.success(fileService.presignDownload(id));
    }
}
