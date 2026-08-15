package com.finaudit.starter.oss;

import com.finaudit.starter.oss.properties.ObjectStorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机冒烟：依赖本机 MinIO（docker run，见 docker-compose.yml）。
 * <p>MinIO 未启动或凭据错误时自动跳过，不阻塞构建；凭据兜底为
 * docker-compose/.env.example 的本地开发默认值。</p>
 */
class S3ObjectStorageServiceSmokeTest {

    private static final String BUCKET = "finaudit-temp"; // 冒烟用临时桶，避免污染默认桶
    private static final String KEY = "smoke/hello.txt";

    private final ObjectStorageService service;
    private boolean usedBucket;

    S3ObjectStorageServiceSmokeTest() {
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setEndpoint("http://127.0.0.1:9000");
        props.setAccessKey(System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin"));
        props.setSecretKey(System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin123"));
        props.setDefaultBucket(BUCKET);
        this.service = new S3ObjectStorageService(props);
    }

    @BeforeEach
    void minioUpOrSkip() {
        // MinIO 不可达则整个类跳过（含依赖 MinIO 的其余断言）
        Assumptions.assumeTrue(probe(), "本机 MinIO 未启动或不可达，跳过真机冒烟");
    }

    @AfterEach
    void cleanup() {
        if (usedBucket) {
            service.deleteObject(BUCKET, KEY);
        }
    }

    private boolean probe() {
        try {
            service.deleteObject(BUCKET, KEY); // 幂等探测：连得上即视为可用
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void fullRoundTrip() {
        byte[] content = "finaudit oss smoke".getBytes(StandardCharsets.UTF_8);
        String fullKey = service.putObject(BUCKET, KEY,
                new ByteArrayInputStream(content), content.length, "text/plain");
        usedBucket = true;

        assertEquals(BUCKET + "/" + KEY, fullKey);
        assertTrue(service.exists(BUCKET, KEY));

        try (InputStream in = service.getObject(BUCKET, KEY)) {
            assertEquals("finaudit oss smoke",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("读取对象失败", e);
        }

        String getUrl = service.presignGetUrl(BUCKET, KEY);
        assertNotNull(getUrl);
        assertTrue(getUrl.startsWith("http://127.0.0.1:9000"));
        assertTrue(getUrl.contains("X-Amz-Signature"));

        String putUrl = service.presignPutUrl(BUCKET, KEY, "text/plain");
        assertNotNull(putUrl);
        assertTrue(putUrl.contains("X-Amz-Signature"));

        service.deleteObject(BUCKET, KEY);
        usedBucket = false;
        assertFalse(service.exists(BUCKET, KEY));
    }
}
