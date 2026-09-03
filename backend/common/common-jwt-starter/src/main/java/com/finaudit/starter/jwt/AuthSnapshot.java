package com.finaudit.starter.jwt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 用户权限快照（P3.5 实时生效机制）：登录/权限变更时由 tenant-service 写入 Redis
 * {@code finaudit:auth:snapshot:{userId}}，网关每请求读取并以快照为<b>权威</b>注入
 * X-User-Roles / X-User-Perms / X-Dept-Id 头——角色/权限变更无需重新登录即生效。
 * <p>快照缺失（Redis 异常/冷启动）时网关降级：角色用 JWT 内 claims，权限置空。</p>
 *
 * @param roles  角色编码列表（快照权威；降级时网关用 JWT claims.roles）
 * @param perms  权限标识符列表（仅快照有；降级为空集 → @RequirePerm 端点 fail-closed）
 * @param deptId 部门 ID（P3.5b 部门实体；未挂部门为 null）
 * @param status 用户状态（1 启用 0 禁用；踢下线由 blackver 机制负责，此字段仅留痕调试）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthSnapshot(List<String> roles, List<String> perms, Long deptId, Integer status) {

    /** 快照序列化用（结构简单无日期，无需 JavaTimeModule） */
    private static final ObjectMapper MAPPER = new JsonMapper();

    public static AuthSnapshot of(List<String> roles, List<String> perms, Long deptId, Integer status) {
        return new AuthSnapshot(
                roles == null ? List.of() : List.copyOf(roles),
                perms == null ? List.of() : List.copyOf(perms),
                deptId, status);
    }

    /**
     * 解析 Redis 快照 JSON；null/空白/解析失败返回 null（调用方走降级路径，不抛异常）。
     */
    public static AuthSnapshot parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, AuthSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 序列化为 JSON 字符串（写 Redis value；StringRedisTemplate 纯字符串约定）。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            // record 结构简单，序列化不应失败；兜底返回仅含空集合的最小结构
            return "{\"roles\":[],\"perms\":[],\"deptId\":null,\"status\":null}";
        }
    }
}
