package com.finaudit.starter.jwt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AuthSnapshot} 快照 JSON 解析单测（P3.5 R1 降级路径）：
 * 快照缺失（null/空白）或损坏（非法 JSON / 类型不符）解析返回 null——网关据此降级：
 * 角色用 JWT claims，权限置空（@RequirePerm 端点 fail-closed 返回 403）。
 */
class AuthSnapshotTest {

    @Test
    void parseValidJson_restoresAllFields() {
        String json = "{\"roles\":[\"admin\"],\"perms\":[\"user:create\",\"audit:approve\"],\"deptId\":5,\"status\":1}";
        AuthSnapshot snapshot = AuthSnapshot.parse(json);
        assertNotNull(snapshot);
        assertEquals(List.of("admin"), snapshot.roles());
        assertEquals(List.of("user:create", "audit:approve"), snapshot.perms());
        assertEquals(5L, snapshot.deptId());
        assertEquals(1, snapshot.status());
    }

    @Test
    void parseNull_returnsNull() {
        assertNull(AuthSnapshot.parse(null));
    }

    @Test
    void parseBlank_returnsNull() {
        assertNull(AuthSnapshot.parse("   "));
    }

    @Test
    void parseCorruptJson_returnsNull() {
        assertNull(AuthSnapshot.parse("{this-is-not-json"));
        // 字段类型不符（roles 应为数组）：降级兜底返回 null，不抛异常
        assertNull(AuthSnapshot.parse("{\"roles\":\"wrong-type\"}"));
    }

    @Test
    void parseIgnoresUnknownFields() {
        AuthSnapshot snapshot = AuthSnapshot.parse("{\"roles\":[\"user\"],\"perms\":[],\"extra\":1}");
        assertNotNull(snapshot);
        assertEquals(List.of("user"), snapshot.roles());
    }

    @Test
    void of_normalizesNullLists() {
        AuthSnapshot snapshot = AuthSnapshot.of(null, null, null, 1);
        assertEquals(List.of(), snapshot.roles());
        assertEquals(List.of(), snapshot.perms());
    }

    @Test
    void toJson_roundTrips() {
        AuthSnapshot snapshot = AuthSnapshot.of(List.of("admin"), List.of("audit:approve"), 3L, 1);
        AuthSnapshot parsed = AuthSnapshot.parse(snapshot.toJson());
        assertNotNull(parsed);
        assertEquals(snapshot, parsed);
    }
}