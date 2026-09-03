package com.finaudit.starter.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Enumeration;

/**
 * Feign 请求头透传：把当前线程 HTTP 请求的鉴权与身份头注入 Feign 出站请求。
 * <p>适用「网关 → 服务 → Feign → 下游」的同步调用链，保证 token（Authorization）与
 * 租户/用户身份头（X-Tenant-Id / X-User-Id / X-Username / X-User-Roles）跨服务传递，
 * 并同步 traceId（X-Trace-Id）。</p>
 * <p>非 HTTP 上下文（如 MQ 消费线程）无请求上下文，自动跳过，由业务显式传参
 * （如按 tenantId 查询），避免误注入脏头。</p>
 */
public class FeignHeaderPropagator implements RequestInterceptor {

    /** 需要跨服务透传的请求头 */
    private static final String[] PROPAGATE_HEADERS = {
            "Authorization",
            "X-Tenant-Id", "X-User-Id", "X-Username", "X-User-Roles", "X-User-Perms", "X-Dept-Id",
            "X-Trace-Id",
    };

    @Override
    public void apply(RequestTemplate template) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return; // 非 HTTP 请求线程（如 MQ 消费），无需透传
        }
        HttpServletRequest request = servletAttrs.getRequest();
        for (String header : PROPAGATE_HEADERS) {
            Enumeration<String> values = request.getHeaders(header);
            if (values == null) {
                continue;
            }
            while (values.hasMoreElements()) {
                template.header(header, values.nextElement());
            }
        }
    }
}
