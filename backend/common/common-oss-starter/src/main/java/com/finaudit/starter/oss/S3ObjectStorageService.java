package com.finaudit.starter.oss;

import com.finaudit.starter.oss.properties.ObjectStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * 基于 AWS S3 SDK v2 的 {@link ObjectStorageService} 实现。
 * 适配 MinIO/腾讯COS 等所有兼容S3协议的存储服务
 * 实现统一对象存储标准接口 ObjectStorageService，提供文件上传、下载、删除、鉴权临时链接、桶初始化能力
 */
public class S3ObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageService.class);

    /** 对象存储配置属性 */
    private final ObjectStorageProperties props;
    /** S3 基础操作客户端（上传/下载/删除/元信息查询） */
    private final S3Client s3Client;
    /** S3 预签名URL生成客户端（生成临时上传/下载链接） */
    private final S3Presigner presigner;

    /**
     * 构造方法：初始化S3客户端与预签名客户端
     * 读取配置文件中oss相关参数，校验endpoint必填，构建凭证与客户端实例
     * @param props 对象存储配置参数（endpoint/ak/sk/区域/路径模式/默认桶/链接过期时间等）
     * @throws IllegalArgumentException 未配置endpoint时抛出启动异常
     */
    public S3ObjectStorageService(ObjectStorageProperties props) {
        this.props = props;
        // 解析存储服务地址
        String endpoint = props.resolveEndpoint();
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少 finaudit.oss.endpoint：请配置对象存储地址（MinIO 默认 http://localhost:9000，"
                            + "COS 需填 S3 兼容 endpoint，如 https://cos.ap-guangzhou.myqcloud.com）");
        }
        // 静态密钥凭证
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
        // S3全局配置：路径访问模式（MinIO必须开启pathStyleAccess=true）
        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(props.isPathStyle())
                .build();
        // 构建基础S3操作客户端
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(props.resolveRegion()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        // 构建预签名URL生成客户端，配置与S3Client保持一致
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(props.resolveRegion()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    /**
     * 获取配置中默认存储桶名称
     * @return 默认桶名
     */
    @Override
    public String defaultBucket() {
        return props.getDefaultBucket();
    }

    /**
     * 文件上传至指定桶
     * @param bucket 存储桶名称
     * @param key 文件唯一存储路径key
     * @param in 文件输入流
     * @param size 文件字节大小
     * @param contentType 文件MIME类型（图片/文档等）
     * @return 完整存储路径 bucket/key
     */
    @Override
    public String putObject(String bucket, String key, InputStream in, long size, String contentType) {
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(in, size));
        return bucket + "/" + key;
    }

    /**
     * 根据桶+key获取文件输入流
     * 调用方使用完成后必须关闭流，避免资源泄漏
     * @param bucket 存储桶名称
     * @param key 文件唯一存储路径key
     * @return 文件二进制输入流
     */
    @Override
    public InputStream getObject(String bucket, String key) {
        return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * 删除桶内指定文件
     * @param bucket 存储桶名称
     * @param key 文件唯一存储路径key
     */
    @Override
    public void deleteObject(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * 判断文件是否存在
     * @param bucket 存储桶名称
     * @param key 文件唯一存储路径key
     * @return true=存在 false=不存在
     */
    @Override
    public boolean exists(String bucket, String key) {
        try {
            // 仅请求文件元数据，不下载完整文件，性能更高
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            // 文件不存在/桶不存在/权限不足均返回false
            return false;
        }
    }

    /**
     * 生成文件临时下载预签名链接，使用默认响应头
     * 链接有效期读取配置 presign-expire-minutes
     * @param bucket 存储桶名称
     * @param key 文件唯一存储路径key
     * @return 临时可访问下载URL
     */
    @Override
    public String presignGetUrl(String bucket, String key) {
        return presignGetUrl(bucket, key, null);
    }

    /**
     * 生成文件临时下载预签名链接，支持自定义下载附件名称响应头
     * @param bucket 存储桶名称
     * @param key 文件唯一存储路径key
     * @param responseContentDisposition 下载附件头，例：attachment;filename=发票.jpg
     * @return 临时可访问下载URL
     */
    @Override
    public String presignGetUrl(String bucket, String key, String responseContentDisposition) {
        GetObjectRequest.Builder requestBuilder = GetObjectRequest.builder().bucket(bucket).key(key);
        // 自定义下载文件名响应头
        if (responseContentDisposition != null && !responseContentDisposition.isBlank()) {
            requestBuilder.responseContentDisposition(responseContentDisposition);
        }
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(props.getPresignExpireMinutes()))
                .getObjectRequest(requestBuilder.build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

    /**
     * 生成文件临时上传预签名链接
     * 前端可直接通过PUT请求上传文件，无需中转后端
     * @param bucket 存储桶名称
     * @param key 文件预存储key
     * @param contentType 文件MIME类型
     * @return 临时上传URL
     */
    @Override
    public String presignPutUrl(String bucket, String key, String contentType) {
        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(props.getPresignExpireMinutes()))
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build())
                .build();
        return presigner.presignPutObject(request).url().toString();
    }

    /**
     * 启动初始化方法：自动校验并创建默认存储桶
     * 执行时机：项目启动阶段
     * 容错逻辑：桶不存在则自动创建；创建失败仅打印警告日志，不阻断应用启动
     * 适配场景：MinIO容器启动慢于Java服务，避免启动流程阻塞
     */
    public void ensureDefaultBucket() {
        String bucket = props.getDefaultBucket();
        try {
            // 查询桶元数据，判断桶是否存在
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            // 桶不存在，执行创建逻辑
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("对象存储默认桶已创建: {}", bucket);
            } catch (Exception createEx) {
                // 创建失败仅告警，后续上传接口会抛出异常，业务自行捕获处理
                log.warn("创建对象存储默认桶失败（{}），后续上传将报错: {}", bucket, createEx.getMessage());
            }
        }
    }
}
