package com.finaudit.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.file.mapper.FileRecordMapper;
import com.finaudit.file.pojo.entity.FileRecord;
import com.finaudit.file.pojo.vo.FileVO;
import com.finaudit.starter.oss.ObjectStorageService;
import com.finaudit.starter.web.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文件业务服务
 * 统一管理文件上传、元数据持久化、文件查询、生成预览/下载临时签名链接
 * 底层依赖 common-oss-starter 提供的对象存储能力，搭配文件元数据表做租户隔离与权限管控
 * 存储分层规则：桶统一，对象Key携带租户ID前缀，实现MinIO等无租户OSS的逻辑隔离
 */
@Service
public class FileService {

    /** 对象存储Key 月份目录格式化器，格式：yyyyMM */
    private static final DateTimeFormatter OBJ_MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 文件后缀 -> HTTP Content-Type 兜底映射，未匹配后缀使用二进制流类型 */
    private static final Map<String, String> EXT_CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("gif", "image/gif"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"));

    private final FileRecordMapper fileRecordMapper;
    /** 对象存储统一操作客户端（MinIO/兼容S3云OSS） */
    private final ObjectStorageService objectStorageService;

    public FileService(FileRecordMapper fileRecordMapper, ObjectStorageService objectStorageService) {
        this.fileRecordMapper = fileRecordMapper;
        this.objectStorageService = objectStorageService;
    }

    /**
     * 文件上传入口
     * 1. 清洗文件名、生成带租户隔离的唯一对象Key
     * 2. 识别文件ContentType
     * 3. 上传文件至OSS默认桶
     * 4. 数据库写入文件元数据记录
     * @param file MultipartFile文件
     * @param tenantId 当前操作租户ID，用于隔离文件资源
     * @return 文件VO，携带预览临时签名链接
     * @throws BizException 文件为空/读取流异常抛出业务异常
     */
    @Transactional
    public FileVO upload(MultipartFile file, Long tenantId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        // 清洗原始文件名，防止路径穿越攻击
        String originalFilename = sanitizeFileName(file.getOriginalFilename());
        // 生成租户隔离唯一存储key
        String objectKey = buildObjectKey(tenantId, originalFilename);
        // 识别文件媒体类型
        String contentType = detectContentType(file, originalFilename);
        try (InputStream in = file.getInputStream()) {
            // 上传至对象存储默认桶
            objectStorageService.putObject(objectKey, in, file.getSize(), contentType);
        } catch (IOException e) {
            throw new BizException("读取上传文件失败");
        }

        // 组装元数据入库
        FileRecord record = FileRecord.from(originalFilename, objectKey, contentType, file.getSize(), tenantId);
        fileRecordMapper.insert(record);
        return toVO(record);
    }

    /**
     * 根据文件ID查询文件元数据，不存在直接抛出业务异常
     * 底层MybatisPlus多租户拦截器自动过滤非当前租户数据，实现数据隔离
     * @param id 文件主键ID
     * @return 文件完整元数据实体
     */
    public FileRecord getRequired(Long id) {
        FileRecord record = fileRecordMapper.selectById(id);
        if (record == null) {
            throw new BizException("文件不存在: " + id);
        }
        return record;
    }

    /**
     * 批量根据文件ID查询文件元数据
     * 用于报销单附件快照、批量展示附件列表场景
     * @param ids 文件ID集合
     * @return 文件元数据列表（自动租户隔离）
     */
    public List<FileRecord> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                .in(FileRecord::getId, ids));
    }

    /**
     * 批量校验附件ID合法性
     * 提交报销单前置校验：全部文件必须存在且属于当前登录租户
     * @param ids 前端传入附件ID列表
     * @throws BizException 存在不存在/不属于当前租户的文件则报错
     */
    public void validateAllOwned(List<Long> ids) {
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.isEmpty()) {
            throw new BizException("文件不存在或不属于当前租户");
        }
        List<FileRecord> found = fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                .in(FileRecord::getId, distinct));
        if (found.size() != distinct.size()) {
            throw new BizException("文件不存在或不属于当前租户");
        }
    }

    /**
     * 生成文件预览临时签名URL（浏览器内联打开，不强制下载）
     * @param id 文件主键ID
     * @return 短期有效预览URL
     */
    public String presignPreview(Long id) {
        FileRecord record = getRequired(id);
        return presignPreviewUrl(record);
    }

    /**
     * 生成文件下载临时签名URL，强制浏览器弹出下载窗口
     * 自动携带原始文件名作为下载附件名称
     * @param id 文件主键ID
     * @return 短期有效下载URL
     */
    public String presignDownload(Long id) {
        FileRecord record = getRequired(id);
        return objectStorageService.presignGetUrl(objectStorageService.defaultBucket(), record.getObjectName(),
                "attachment; filename=\"" + record.getFileName() + "\"");
    }

    /**
     * 文件实体转换对外VO，自动携带实时生成的预览签名链接
     * @param record 数据库文件元数据实体
     * @return 前端展示文件VO
     */
    public FileVO toVO(FileRecord record) {
        String url = record.getObjectName() == null ? null : presignPreviewUrl(record);
        return FileVO.from(record, url);
    }

    /**
     * 内部工具：根据文件元数据生成预览签名链接，避免重复查询DB
     * @param record 文件元数据实体
     * @return 预览临时URL
     */
    private String presignPreviewUrl(FileRecord record) {
        return objectStorageService.presignGetUrl(record.getObjectName());
    }

    /**
     * 静态工具：生成OSS唯一对象存储Key
     * 路径结构：{租户ID}/{年月}/{UUID}.后缀
     * 作用：MinIO等无原生多租户OSS实现逻辑隔离，避免不同企业文件重名覆盖
     * @param tenantId 租户ID
     * @param originalFilename 原始文件名
     * @return 隔离型唯一objectKey
     */
    static String buildObjectKey(Long tenantId, String originalFilename) {
        String ext = "";
        int dot = originalFilename.lastIndexOf('.');
        // 合法后缀截取，限制字母数字，防止异常后缀
        if (dot >= 0 && dot < originalFilename.length() - 1) {
            String candidate = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{1,10}")) {
                ext = "." + candidate;
            }
        }
        return tenantId + "/" + LocalDate.now().format(OBJ_MONTH_FMT) + "/" + UUID.randomUUID() + ext;
    }

    /**
     * 静态工具：清洗文件名，防御路径穿越漏洞
     * 剔除所有目录路径，仅保留纯文件名；空文件名兜底为unnamed
     * @param name 原始上传文件名
     * @return 安全纯净文件名
     */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        String cleaned = name.replace('\\', '/');
        // 统一分隔符为/，截取最后一段纯文件名
        int idx = cleaned.lastIndexOf('/');
        String base = idx >= 0 ? cleaned.substring(idx + 1) : cleaned;
        return base.isBlank() ? "unnamed" : base;
    }

    /**
     * 静态工具：自动识别文件ContentType
     * 优先使用MultipartFile自带MIME类型，无则根据后缀匹配内置映射，兜底二进制流
     * @param file 上传文件
     * @param fileName 清洗后文件名
     * @return HTTP标准Content-Type
     */
    private static String detectContentType(MultipartFile file, String fileName) {
        String provided = file.getContentType();
        // 优先使用前端携带的标准媒体类型，排除通用二进制兜底值
        if (provided != null && !provided.isBlank() && !"application/octet-stream".equalsIgnoreCase(provided)) {
            return provided;
        }
        // 按后缀匹配预设图片/文档类型
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String mapped = EXT_CONTENT_TYPES.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (mapped != null) {
                return mapped;
            }
        }
        // 无匹配后缀默认二进制文件
        return "application/octet-stream";
    }
}
