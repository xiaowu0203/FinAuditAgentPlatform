package com.finaudit.agentcore.util;

/**
 * 财务角色常量工具类
 * <p>存放财务相关角色编码，提供角色判断工具方法</p>
 */
public final class FinanceRoles {

    private FinanceRoles() {
    }

    /**
     * 判断用户角色串是否属于财务角色
     * <p>支持逗号分隔多个角色字符串，空格会自动trim去除；
     * 满足 admin 或者 auditor 任一角色即判定为财务角色</p>
     * @param roles 逗号分隔的角色字符串，例如 "admin,user,auditor"；允许null/空白字符串
     * @return true：包含财务角色；false：无财务角色
     */
    public static boolean isFinance(String roles) {
        if (roles == null || roles.isBlank()) {
            return false;
        }
        for (String r : roles.split(",")) {
            String role = r.trim();
            if ("admin".equals(role) || "auditor".equals(role)) {
                return true;
            }
        }
        return false;
    }
}
