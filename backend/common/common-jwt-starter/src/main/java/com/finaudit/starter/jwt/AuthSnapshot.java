package com.finaudit.starter.jwt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 用户权限认证快照 Record
 * <p>存入Redis，缓存用户角色、权限、部门、账号状态；用户权限变更事件触发更新/删除快照</p>
 * <p>用于网关/过滤器快速获取用户权限，避免每次登录都查询数据库；不可变Record，内部集合做不可变拷贝防止外部篡改</p>
 * @param roles 角色编码列表
 * @param perms 权限标识符列表
 * @param deptId 用户部门ID
 * @param status 用户账号状态：1启用，0禁用
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthSnapshot(List<String> roles, List<String> perms, Long deptId, Integer status) {

    /**
     * 快照专用Jackson实例
     * <p>Record结构简单，不含日期类型，无需JavaTimeModule，减少多余模块加载</p>
     */
    private static final ObjectMapper MAPPER = new JsonMapper();

    /**
     * 构建不可变权限快照对象，对入参集合做防御拷贝，防止外部List被修改影响快照内部数据
     * @param roles 角色编码列表，null转为空不可变集合
     * @param perms 权限标识符列表，null转为空不可变集合
     * @param deptId 部门ID
     * @param status 用户账号状态
     * @return 不可变AuthSnapshot实例
     */
    public static AuthSnapshot of(List<String> roles, List<String> perms, Long deptId, Integer status) {
        return new AuthSnapshot(
                roles == null ? List.of() : List.copyOf(roles),
                perms == null ? List.of() : List.copyOf(perms),
                deptId, status);
    }

    /**
     * 解析Redis中读取的快照JSON字符串
     * <p>容错设计：入参null/空白、JSON解析异常全部返回null，不抛出异常，交给上层执行降级逻辑</p>
     * @param json Redis读取到的快照json字符串
     * @return 快照对象；解析失败返回null
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

    /**
     * 将快照序列化为JSON字符串，用于写入Redis（StringRedisTemplate字符串存储约定）
     * <p>兜底：理论上Record不会序列化失败，捕获异常返回最小有效空JSON，避免写入Redis失败</p>
     * @return json字符串
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            // record结构固定简单，正常不会走到异常分支；兜底返回空集合结构，防止Redis写入异常
            return "{\"roles\":[],\"perms\":[],\"deptId\":null,\"status\":null}";
        }
    }
}
