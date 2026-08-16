package com.finaudit.starter.oss;

/**
 * 对象存储 Provider。
 * <p>MinIO 与腾讯云 COS 均走 S3 协议，Provider 仅决定默认 endpoint/region 等缺省值，
 * 业务代码零感知。</p>
 */
public enum ObjectStorageProvider {

    /** 本地/开源环境（docker-compose 一键起），默认 endpoint http://localhost:9000 */
    MINIO,

    /** 腾讯云 COS（S3 兼容），需显式配置 endpoint（如 https://cos.ap-guangzhou.myqcloud.com）与 region */
    COS
}
