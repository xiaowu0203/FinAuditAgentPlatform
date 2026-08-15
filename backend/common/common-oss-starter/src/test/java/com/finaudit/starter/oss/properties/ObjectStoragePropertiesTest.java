package com.finaudit.starter.oss.properties;

import com.finaudit.starter.oss.ObjectStorageProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置默认值语义测试：MinIO 为默认 Provider，endpoint/region/bucket 兜底生效。
 */
class ObjectStoragePropertiesTest {

    @Test
    void minioDefaults() {
        ObjectStorageProperties props = new ObjectStorageProperties();
        assertEquals(ObjectStorageProvider.MINIO, props.getProvider());
        assertEquals("http://localhost:9000", props.resolveEndpoint());
        assertEquals("us-east-1", props.resolveRegion());
        assertEquals("finaudit-file", props.getDefaultBucket());
        assertTrue(props.isPathStyle());
        assertEquals(15, props.getPresignExpireMinutes());
    }

    @Test
    void explicitConfigWins() {
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setProvider(ObjectStorageProvider.COS);
        props.setEndpoint("https://cos.ap-guangzhou.myqcloud.com");
        props.setRegion("ap-guangzhou");
        props.setDefaultBucket("biz-bucket");
        props.setPathStyle(false);

        assertEquals("https://cos.ap-guangzhou.myqcloud.com", props.resolveEndpoint());
        assertEquals("ap-guangzhou", props.resolveRegion());
        assertEquals("biz-bucket", props.getDefaultBucket());
        assertTrue(!props.isPathStyle());
    }

    @Test
    void cosWithoutEndpointGivesBlank() {
        // COS 必须显式 endpoint；resolveEndpoint 返回空串，由 S3ObjectStorageService 构造期抛错兜底
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setProvider(ObjectStorageProvider.COS);
        assertEquals("", props.resolveEndpoint());
    }
}
