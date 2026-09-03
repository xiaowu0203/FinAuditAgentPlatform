package com.finaudit.starter.web.auth;

import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 用户登录上下文对象
 * <p>由Token解析过滤器构建，存放当前登录用户会话信息，存入ThreadLocal({@code UserContextHolder})，供拦截器、业务层读取</p>
 * <p>部分字段可从请求Header（X‑User‑Id / X‑Tenant‑Id等）解析得到；JWT或者Redis权限快照来源</p>
 */
@Data
public class UserContext {

    /**
     * 用户ID，来源于请求头 X‑User‑Id
     */
    private Long userId;

    /**
     * 租户ID，来源于请求头 X‑Tenant‑Id
     * <p>与 {@code TenantContextHolder} 租户上下文同源，保证全链路租户一致</p>
     */
    private Long tenantId;

    /**
     * 登录用户名，来源于请求头 X‑Username
     */
    private String username;

    /**
     * 角色编码集合，来源于请求头 X‑User‑Roles（原始为逗号分隔字符串，解析为List）
     * <p>JWT降级场景直接携带角色快照；完整权限优先使用perms集合</p>
     */
    private List<String> roles;

    /**
     * 权限标识符集合，来源于请求头 X‑User‑Perms（原始为逗号分隔字符串，解析为Set）
     * <p>存放系统操作级、业务资源级权限编码，用于接口权限判断，对应@RequirePerm注解校验</p>
     */
    private Set<String> perms;

    /**
     * 用户所属部门ID，来源于请求头 X‑Dept‑Id
     */
    private Long deptId;

    /**
     * 判断用户是否拥有某一个指定权限
     * @param code 权限标识符
     * @return true=拥有该权限；perms为null直接返回false
     */
    public boolean hasPerm(String code) {
        return perms != null && perms.contains(code);
    }

    /**
     * 多个权限，满足任意一个即返回true
     * <p>对应注解{@link RequirePerm}的“或”校验语义，供PermissionInterceptor调用</p>
     * @param codes 待校验的多个权限标识符数组
     * @return true：命中任意一个权限；false：无匹配权限 / 参数为空
     */
    public boolean hasAnyPerm(String... codes) {
        if (perms == null || codes == null) {
            return false;
        }
        for (String code : codes) {
            if (perms.contains(code)) {
                return true;
            }
        }
        return false;
    }
}
