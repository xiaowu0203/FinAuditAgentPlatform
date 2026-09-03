package com.finaudit.file.controller;

import com.finaudit.file.pojo.entity.FileRecord;
import com.finaudit.file.pojo.vo.FileVO;
import com.finaudit.file.service.FileService;
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

import java.util.List;

/**
 * 文件接口（纯二进制资源：上传 / 详情 / 预览 / 下载）。
 * <p>上传仅前端对接本服务；业务服务读文件一律经 FileServiceFeign 调用。</p>
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
                            @RequestHeader(name = "X-Tenant-Id", defaultValue = "1") Long tenantId,
                            @RequestHeader(name = "X-User-Id", required = false) Long userId) {
        return R.success(fileService.upload(file, tenantId, userId));
    }

    @Operation(summary = "文件详情（含预签名预览 URL）")
    @GetMapping("/{id}")
    public R<FileVO> detail(@PathVariable Long id) {
        FileRecord record = fileService.getRequired(id);
        return R.success(fileService.toVO(record));
    }

    @Operation(summary = "文件批量详情（按 id 逗号分隔，供业务服务组快照）")
    @GetMapping
    public R<List<FileVO>> batchDetail(@RequestParam("ids") List<Long> ids) {
        return R.success(fileService.listByIds(ids).stream().map(fileService::toVO).toList());
    }

    @Operation(summary = "预览预签名 URL（浏览器内联渲染）")
    @GetMapping("/{id}/preview")
    public R<String> preview(@PathVariable Long id) {
        return R.success(fileService.presignPreview(id));
    }

    @Operation(summary = "下载预签名 URL（响应头带 Content-Disposition: attachment）")
    @GetMapping("/{id}/download")
    public R<String> download(@PathVariable Long id) {
        return R.success(fileService.presignDownload(id));
    }
}
