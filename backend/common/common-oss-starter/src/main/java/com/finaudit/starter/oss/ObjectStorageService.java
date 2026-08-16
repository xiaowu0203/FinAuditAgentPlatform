package com.finaudit.starter.oss;

import java.io.InputStream;

/**
 * 对象存储统一抽象（S3 协议）。
 * <p>不带 bucket 参数的便捷方法使用默认桶 {@link #defaultBucket()}；
 * {@link #putObject} 返回对象完整 key（{@code bucket + "/" + key}），便于 DB 落库后可追溯。</p>
 */
public interface ObjectStorageService {

    /** 默认桶名 */
    String defaultBucket();

    // ---------- 上传 ----------

    /** 上传到默认桶，返回完整对象 key */
    default String putObject(String key, InputStream in, long size, String contentType) {
        return putObject(defaultBucket(), key, in, size, contentType);
    }

    /** 上传到指定桶，返回完整对象 key */
    String putObject(String bucket, String key, InputStream in, long size, String contentType);

    // ---------- 读取 ----------

    /** 读取默认桶对象内容（调用方负责关闭流） */
    default InputStream getObject(String key) {
        return getObject(defaultBucket(), key);
    }

    /** 读取指定桶对象内容（调用方负责关闭流） */
    InputStream getObject(String bucket, String key);

    // ---------- 删除 ----------

    /** 删除默认桶对象（对象不存在不报错） */
    default void deleteObject(String key) {
        deleteObject(defaultBucket(), key);
    }

    /** 删除指定桶对象（对象不存在不报错） */
    void deleteObject(String bucket, String key);

    // ---------- 存在性 ----------

    /** 默认桶对象是否存在 */
    default boolean exists(String key) {
        return exists(defaultBucket(), key);
    }

    /** 指定桶对象是否存在 */
    boolean exists(String bucket, String key);

    // ---------- 预签名 URL ----------

    /** 默认桶只读预签名 URL（默认有效期内可下载/直读） */
    default String presignGetUrl(String key) {
        return presignGetUrl(defaultBucket(), key);
    }

    /** 指定桶只读预签名 URL */
    String presignGetUrl(String bucket, String key);

    /**
     * 指定桶只读预签名 URL，带响应内容处置头（下载传 {@code attachment; filename="..."}；
     * 预览传 null 走 {@link #presignGetUrl(String, String)}。SDK 会对 header 值做 URL 编码，中文/空格安全）。
     */
    String presignGetUrl(String bucket, String key, String responseContentDisposition);

    /** 默认桶可写预签名 URL（前端直传，默认有效期） */
    default String presignPutUrl(String key, String contentType) {
        return presignPutUrl(defaultBucket(), key, contentType);
    }

    /** 指定桶可写预签名 URL（前端直传） */
    String presignPutUrl(String bucket, String key, String contentType);
}
