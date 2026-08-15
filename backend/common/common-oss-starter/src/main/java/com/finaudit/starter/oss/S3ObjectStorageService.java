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
 * <p>MinIO 与腾讯云 COS 均提供 S3 兼容 endpoint，本实现仅靠
 * {@code endpoint/region/accessKey/secretKey} 配置即可在两者间切换。</p>
 */
public class S3ObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageService.class);

    private final ObjectStorageProperties props;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    public S3ObjectStorageService(ObjectStorageProperties props) {
        this.props = props;
        String endpoint = props.resolveEndpoint();
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少 finaudit.oss.endpoint：请配置对象存储地址（MinIO 默认 http://localhost:9000，"
                            + "COS 需填 S3 兼容 endpoint，如 https://cos.ap-guangzhou.myqcloud.com）");
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(props.isPathStyle())
                .build();
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(props.resolveRegion()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(props.resolveRegion()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    @Override
    public String defaultBucket() {
        return props.getDefaultBucket();
    }

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

    @Override
    public InputStream getObject(String bucket, String key) {
        return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deleteObject(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            return false;
        }
    }

    @Override
    public String presignGetUrl(String bucket, String key) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(props.getPresignExpireMinutes()))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

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
     * 确保默认桶存在（不存在则创建）。启动期调用，失败仅告警不中断启动，
     * 避免 MinIO 晚于应用启动导致启动失败。
     */
    public void ensureDefaultBucket() {
        String bucket = props.getDefaultBucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("对象存储默认桶已创建: {}", bucket);
            } catch (Exception createEx) {
                log.warn("创建对象存储默认桶失败（{}），后续上传将报错: {}", bucket, createEx.getMessage());
            }
        }
    }
}
