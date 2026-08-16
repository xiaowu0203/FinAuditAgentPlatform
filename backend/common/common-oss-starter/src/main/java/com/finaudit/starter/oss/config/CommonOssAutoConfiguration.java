package com.finaudit.starter.oss.config;

import com.finaudit.starter.oss.ObjectStorageProvider;
import com.finaudit.starter.oss.ObjectStorageService;
import com.finaudit.starter.oss.S3ObjectStorageService;
import com.finaudit.starter.oss.properties.ObjectStorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 对象存储自动配置。
 * <p>仅在 {@code finaudit.oss.enabled=true} 时生效，避免未使用对象存储的服务被无谓拉起 S3 连接。
 * 生效时启动自检 S3 凭据：accessKey/secretKey 缺失（或 COS 提供方未配 endpoint）直接失败并给出明确提示，
 * 替代运行期首个请求才暴露的 403/InvalidAccessKeyId 或空 endpoint 报错。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "finaudit.oss", name = "enabled", havingValue = "true")
@ConditionalOnClass(S3Client.class)
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class CommonOssAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStorageService.class)
    public S3ObjectStorageService objectStorageService(ObjectStorageProperties props) {
        if (isBlank(props.getAccessKey()) || isBlank(props.getSecretKey())) {
            throw new IllegalStateException(
                    "对象存储凭据未配置：请设置 finaudit.oss.access-key / secret-key"
                            + "（凭据禁止入库，经环境变量注入），当前已启用 finaudit.oss.enabled=true");
        }
        if (props.getProvider() == ObjectStorageProvider.COS && isBlank(props.getEndpoint())) {
            throw new IllegalStateException(
                    "COS 提供方需配置 finaudit.oss.endpoint（如 https://cos.ap-guangzhou.myqcloud.com）；"
                            + "MinIO 提供方可不配，走默认 localhost:9000");
        }
        S3ObjectStorageService service = new S3ObjectStorageService(props);
        service.ensureDefaultBucket();
        return service;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
