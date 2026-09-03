package com.finaudit.starter.web.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionInterceptor} 单测（P3.5 R1）：
 * 有权限放行 / 任一码命中放行 / 无权限 403 / 无注解放行 / 无上下文 fail-closed / 非 Controller 放行。
 */
class PermissionInterceptorTest {

    private final PermissionInterceptor interceptor = new PermissionInterceptor();

    /** 测试用 Controller：无注解方法 + 单码注解 + 任一码注解。 */
    static class DemoController {
        public void open() {
        }

        @RequirePerm("user:create")
        public void single() {
        }

        @RequirePerm({"audit:viewAll", "audit:approve"})
        public void anyOf() {
        }
    }

    private final DemoController controller = new DemoController();

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    private HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(controller, DemoController.class.getMethod(method));
    }

    private UserContext contextWithPerms(String... codes) {
        UserContext context = new UserContext();
        context.setUserId(1L);
        context.setTenantId(1L);
        context.setPerms(new LinkedHashSet<>(List.of(codes)));
        return context;
    }

    @Test
    void noAnnotation_passesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, handler("open"));
        assertTrue(allowed);
        assertEquals(200, response.getStatus()); // 未写 403，状态保持默认
    }

    @Test
    void hasPerm_allows() throws Exception {
        UserContextHolder.set(contextWithPerms("user:create"));
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("single"));
        assertTrue(allowed);
    }

    @Test
    void anyOf_anyOneMatchAllows() throws Exception {
        // 仅命中「任一码」中的第二个，也应放行
        UserContextHolder.set(contextWithPerms("reimb:viewAll", "audit:approve"));
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("anyOf"));
        assertTrue(allowed);
    }

    @Test
    void missingPerm_forbids403() throws Exception {
        UserContextHolder.set(contextWithPerms("user:list"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, handler("single"));
        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("无权限访问"));
    }

    @Test
    void noContext_failsClosed() throws Exception {
        // 未登录/内部直连无上下文 → 视为无任何权限，403 fail-closed
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(), response, handler("single"));
        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void nonHandler_skipped() throws Exception {
        // 非 HandlerMethod（静态资源等）不做权限校验
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());
        assertTrue(allowed);
    }
}