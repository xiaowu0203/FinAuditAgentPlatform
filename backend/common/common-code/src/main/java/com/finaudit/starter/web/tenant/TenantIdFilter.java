package com.finaudit.starter.web.tenant;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 从请求头 {@code X-Tenant-Id} 写入租户上下文，请求结束清理。
 * <p>P1.4 起网关从 JWT 注入真实租户；直连服务（无头）时不设置，由多租户拦截器回退默认租户。</p>
 */
public class TenantIdFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TenantIdFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String header = httpRequest.getHeader(TenantContextHolder.TENANT_ID_HEADER);
        if (header != null && !header.isBlank()) {
            try {
                TenantContextHolder.setTenantId(Long.valueOf(header.trim()));
            } catch (NumberFormatException e) {
                log.warn("X-Tenant-Id 非法，忽略: {}", header);
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
