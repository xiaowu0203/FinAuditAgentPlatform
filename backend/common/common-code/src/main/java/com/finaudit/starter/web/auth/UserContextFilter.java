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
 * 从网关注入的身份请求头解析 {@link UserContext} 写入 {@link UserContextHolder}，请求结束清理。
 * <p>X-User-Id 缺失（白名单接口/内部直连）时不设置上下文——后续 {@code @RequirePerm}
 * 拦截器对无上下文请求 fail-closed。头值只信任网关（网关已剥离客户端伪造的身份头）。</p>
 */
public class UserContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        try {
            Long userId = parseLong(httpRequest.getHeader(UserContextHolder.USER_ID_HEADER));
            // 无用户身份头（白名单/匿名）不建上下文
            if (userId != null) {
                UserContextHolder.set(buildContext(httpRequest, userId));
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private UserContext buildContext(HttpServletRequest request, Long userId) {
        UserContext context = new UserContext();
        context.setUserId(userId);
        context.setTenantId(parseLong(request.getHeader("X-Tenant-Id")));
        context.setUsername(blankToNull(request.getHeader(UserContextHolder.USERNAME_HEADER)));
        context.setRoles(splitCsv(request.getHeader(UserContextHolder.USER_ROLES_HEADER)));
        context.setPerms(new LinkedHashSet<>(splitCsv(request.getHeader(UserContextHolder.USER_PERMS_HEADER))));
        context.setDeptId(parseLong(request.getHeader(UserContextHolder.DEPT_ID_HEADER)));
        return context;
    }

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

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** 逗号分隔转 List（trim + 去空串）；null/空白返回空 List。 */
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
