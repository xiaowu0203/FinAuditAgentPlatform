package com.finaudit.starter.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finaudit.starter.web.result.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * {@link RequirePerm} 权限标识符校验拦截器。
 * <p>方法级注解优先于类级；任一权限码命中（any-of）放行；
 * 上下文缺失或全部未命中 → HTTP 403 + 统一 R JSON（fail-closed，管理端 Redis 快照
 * 降级期间短暂不可用是安全取舍，业务端点未挂注解不受影响）。</p>
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);

    /** 独立 ObjectMapper：拦截器直接写响应体，不经过 Spring MVC 消息转换器 */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 非 Controller 方法（静态资源等）放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePerm requirePerm = resolveAnnotation(handlerMethod);
        if (requirePerm == null) {
            return true; // opt-in：未标注不做权限校验
        }
        UserContext context = UserContextHolder.get();
        if (context == null || !context.hasAnyPerm(requirePerm.value())) {
            log.warn("权限校验拒绝: uri={}, userId={}, required={}",
                    request.getRequestURI(),
                    context == null ? null : context.getUserId(),
                    String.join("|", requirePerm.value()));
            writeForbidden(response);
            return false;
        }
        return true;
    }

    /** 方法级注解优先；方法未标注时取类级（Controller 整组收口）。 */
    private RequirePerm resolveAnnotation(HandlerMethod handlerMethod) {
        RequirePerm methodLevel = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (methodLevel != null) {
            return methodLevel;
        }
        return handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
    }

    /** 写 HTTP 403 + 统一 R JSON（与网关 401 响应体风格一致）。 */
    private void writeForbidden(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(R.fail(403, "无权限访问")));
    }
}
