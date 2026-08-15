package com.finaudit.file.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对象 key 生成：租户前缀 {tenantId}/{yyyyMM}/{uuid}{ext}，天然防跨租户碰撞与路径穿越。
 */
class ObjectKeyTest {

    @Test
    void tenantPrefixWithExt() {
        String key = FileService.buildObjectKey(1L, "report.pdf");
        assertTrue(key.matches("^1/\\d{6}/[0-9a-f-]{36}\\.pdf$"), "key 格式不正确: " + key);
    }

    @Test
    void noExtFallback() {
        String key = FileService.buildObjectKey(3L, "noext");
        assertTrue(key.matches("^3/\\d{6}/[0-9a-f-]{36}$"), "key 格式不正确: " + key);
    }

    @Test
    void noPathTraversal() {
        // 路径成分不影响 key（始终 tenant/uuid），非法路径不含 ".."
        String key = FileService.buildObjectKey(2L, "../../etc/passwd");
        assertFalse(key.contains(".."), "key 不应包含路径穿越: " + key);
        assertFalse(key.contains("etc"), "key 不应包含原始路径: " + key);
        assertTrue(key.startsWith("2/"));
    }
}
