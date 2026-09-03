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
 * 接口权限拦截器
 * <p>基于自定义注解 {@link RequirePerm} 做接口粒度权限校验；Opt‑in模式：不加注解直接放行</p>
 * <p>优先级：方法上注解 > Controller类注解；拦截到无权限直接输出403 JSON响应，不再进入Controller逻辑</p>
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);

    /**
     * 独立Jackson ObjectMapper实例
     * <p>拦截器层直接输出JSON响应，不经过SpringMVC消息转换器；注册时间模块统一时间序列化格式</p>
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /**
     * Controller执行前拦截校验
     * @param request 请求对象
     * @param response 响应对象
     * @param handler 请求处理器
     * @return true放行；false阻断请求，后续Controller不会执行
     * @throws Exception 输出JSON序列化异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 非Controller方法（静态资源、视图控制器等）直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 解析接口上的@RequirePerm权限注解
        RequirePerm requirePerm = resolveAnnotation(handlerMethod);
        if (requirePerm == null) {
            // opt‑in机制：没有标注权限注解，不校验直接放行
            return true;
        }

        // 获取当前登录用户上下文（从ThreadLocal获取）
        UserContext context = UserContextHolder.get();
        // 用户未登录 或者 不具备要求的任意权限，返回403拒绝访问
        if (context == null || !context.hasAnyPerm(requirePerm.value())) {
            log.warn("权限校验拒绝: uri={}, userId={}, required={}",
                    request.getRequestURI(),
                    context == null ? null : context.getUserId(),
                    String.join("|", requirePerm.value()));
            // 输出统一403 JSON响应
            writeForbidden(response);
            return false;
        }
        // 权限校验通过，放行进入Controller
        return true;
    }

    /**
     * 解析{@link RequirePerm}注解
     * <p>优先取方法上注解；方法无注解，则取Controller类上注解，实现整个Controller批量权限收口</p>
     * @param handlerMethod handler方法元数据
     * @return RequirePerm注解实例；返回null代表未配置权限
     */
    private RequirePerm resolveAnnotation(HandlerMethod handlerMethod) {
        // 优先读取方法级别注解
        RequirePerm methodLevel = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (methodLevel != null) {
            return methodLevel;
        }
        // 方法无注解，读取Controller类级别注解
        return handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
    }

    /**
     * 输出403无权限统一JSON响应
     * <p>拦截器层直接写response输出，与网关层401响应格式保持一致</p>
     * @param response http响应
     * @throws Exception JSON序列化写出异常
     */
    private void writeForbidden(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(R.fail(403, "无权限访问")));
    }
}
