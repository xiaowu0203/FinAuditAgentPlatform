package com.finaudit.starter.web.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户上下文过滤器
 * <p>读取网关透传的HTTP请求头，构建{@link UserContext}用户会话上下文，存入ThreadLocal</p>
 * <p>逻辑：存在用户ID请求头则构建上下文；匿名请求直接跳过；finally块强制清理ThreadLocal，防止线程池复用上下文泄露</p>
 * <p>注意：身份、权限数据由网关前置解析并放入Header，本Filter不做token校验，只做上下文组装</p>
 */
public class UserContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);

    /**
     * 过滤器主逻辑
     * @param request servlet请求
     * @param response servlet响应
     * @param filterChain 过滤器链
     * @throws IOException IO异常
     * @throws ServletException Servlet异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        try {
            // 从请求头解析用户ID
            Long userId = parseLong(httpRequest.getHeader(UserContextHolder.USER_ID_HEADER));
            // 没有用户ID头：匿名/白名单接口，不创建用户上下文，直接放行
            if (userId != null) {
                UserContextHolder.set(buildContext(httpRequest, userId));
            }
            // 继续执行后续过滤器、Controller逻辑
            filterChain.doFilter(request, response);
        } finally {
            // 【强制】无论是否异常，请求结束清空ThreadLocal，线程池复用时防止上下文泄露、串用户
            UserContextHolder.clear();
        }
    }

    /**
     * 从Http请求头完整构建UserContext上下文对象
     * @param request http请求，读取各类X‑*身份Header
     * @param userId 已经解析完成的用户ID
     * @return 组装完成的用户上下文
     */
    private UserContext buildContext(HttpServletRequest request, Long userId) {
        UserContext context = new UserContext();
        context.setUserId(userId);
        context.setTenantId(parseLong(request.getHeader("X-Tenant-Id")));
        context.setUsername(blankToNull(request.getHeader(UserContextHolder.USERNAME_HEADER)));
        context.setRoles(splitCsv(request.getHeader(UserContextHolder.USER_ROLES_HEADER)));
        // 使用LinkedHashSet，保留权限顺序同时去重
        context.setPerms(new LinkedHashSet<>(splitCsv(request.getHeader(UserContextHolder.USER_PERMS_HEADER))));
        context.setDeptId(parseLong(request.getHeader(UserContextHolder.DEPT_ID_HEADER)));
        return context;
    }

    /**
     * 将header字符串安全解析为Long
     * @param header 请求头原始字符串
     * @return 解析成功返回Long；空/空白/格式错误返回null，并打印警告日志
     */
    private Long parseLong(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException e) {
            log.warn("身份请求头数值非法，忽略: {}", header);
            return null;
        }
    }

    /**
     * 空白字符串转为null；避免把全空格存入上下文
     * @param value 原始header值
     * @return trim之后的字符串，空白则返回null
     */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * 把逗号分隔的CSV字符串拆分为字符串List；空输入返回空List（此处省略实现，与现有代码保持一致）
     * @param header 逗号分隔字符串
     * @return 拆分后的列表
     */
    private List<String> splitCsv(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
