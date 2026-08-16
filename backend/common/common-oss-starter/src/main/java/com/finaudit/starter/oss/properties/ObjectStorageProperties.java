package com.finaudit.starter.oss.properties;

import com.finaudit.starter.oss.ObjectStorageProvider;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置（前缀 {@code finaudit.oss}）。
 * <p>凭据（accessKey/secretKey）禁止入库，一律经环境变量注入。</p>
 */
@ConfigurationProperties(prefix = "finaudit.oss")
@Getter
@Setter
public class ObjectStorageProperties {

    /** 是否启用对象存储；关闭时自动配置不加载任何 Bean */
    private boolean enabled = false;
    /** Provider：决定默认 endpoint/region（MinIO 与 COS 均走 S3 协议） */
    private ObjectStorageProvider provider = ObjectStorageProvider.MINIO;
    /** S3 兼容 endpoint：MinIO 默认 http://localhost:9000；COS 需填如 https://cos.ap-guangzhou.myqcloud.com */
    private String endpoint;
    /** Access Key */
    private String accessKey;
    /** Secret Key（环境变量注入） */
    private String secretKey;
    /** Region：MinIO 默认 us-east-1（无效可忽略）；COS 需与桶所在地域一致，如 ap-guangzhou */
    private String region;
    /** 默认桶（docker-compose 的 minio-init 已预建 finaudit-file） */
    private String defaultBucket = "finaudit-file";
    /** 路径风格寻址：MinIO 必须 true；COS 虚拟主机风格时设 false */
    private boolean pathStyle = true;
    /** 预签名 URL 默认有效期（分钟） */
    private int presignExpireMinutes = 15;

    /** 生效 endpoint：显式配置优先，否则按 Provider 默认 */
    public String resolveEndpoint() {
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        return provider == ObjectStorageProvider.COS ? "" : "http://localhost:9000";
    }

    /** 生效 region：显式配置优先，否则默认 us-east-1 */
    public String resolveRegion() {
        if (region != null && !region.isBlank()) {
            return region;
        }
        return "us-east-1";
    }

}
