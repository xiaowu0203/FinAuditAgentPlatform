package com.finaudit.starter.oss.config;

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
 * <p>仅在 {@code finaudit.oss.enabled=true} 时生效，避免未使用对象存储的服务被无谓拉起 S3 连接。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "finaudit.oss", name = "enabled", havingValue = "true")
@ConditionalOnClass(S3Client.class)
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class CommonOssAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStorageService.class)
    public S3ObjectStorageService objectStorageService(ObjectStorageProperties props) {
        S3ObjectStorageService service = new S3ObjectStorageService(props);
        service.ensureDefaultBucket();
        return service;
    }
}
