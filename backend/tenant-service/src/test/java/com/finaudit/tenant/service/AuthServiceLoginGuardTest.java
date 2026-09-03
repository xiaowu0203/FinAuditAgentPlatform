package com.finaudit.tenant.service;

import com.finaudit.starter.jwt.JwtProperties;
import com.finaudit.starter.jwt.JwtTokenProvider;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.pojo.dto.LoginRequest;
import com.finaudit.tenant.pojo.entity.SysTenant;
import com.finaudit.tenant.pojo.entity.SysUser;
import com.finaudit.tenant.pojo.vo.LoginVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录加固单测（P3.5d）：防爆破锁定拦截、账号存在性泄露修复
 * （禁用判断后置到密码验证之后 / 未知用户统一文案 + 固定哈希同价比对）、成功清零失败计数。
 */
class AuthServiceLoginGuardTest {

    private static final String TENANT_CODE = "default";

    private final SysTenantService tenantService = mock(SysTenantService.class);
    private final SysUserService userService = mock(SysUserService.class);
    private final SysUserRoleService userRoleService = mock(SysUserRoleService.class);
    private final SysRoleService roleService = mock(SysRoleService.class);
    private final SysPermissionService permissionService = mock(SysPermissionService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final JwtProperties jwtProperties = mock(JwtProperties.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);

    private final AuthService authService = new AuthService(
            tenantService, userService, userRoleService, roleService, permissionService,
            passwordEncoder, jwtTokenProvider, jwtProperties, authSessionService);

    @BeforeEach
    void stubTenant() {
        SysTenant tenant = new SysTenant();
        tenant.setId(1L);
        tenant.setStatus(1);
        when(tenantService.getByCode(TENANT_CODE)).thenReturn(tenant);
    }

    @Test
    void lockedAccountRejectedBeforeAnyUserLookup() {
        BizException locked = new BizException("登录失败次数过多，账号已临时锁定");
        // assertLoginAllowed 返回 void，须用 doThrow 语法
        doThrow(locked).when(authSessionService).assertLoginAllowed(1L, "alice");

        BizException ex = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("alice", "pwd", TENANT_CODE)));

        assertEquals("登录失败次数过多，账号已临时锁定", ex.getMessage());
        // 锁定中不查用户、不比密码，直接拒绝
        verify(userService, never()).getByTenantAndUsername(anyLong(), anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void disabledUserWithWrongPasswordGetsGenericError() {
        // 账号存在且被禁用，但密码错误：必须报「用户名或密码错误」而非「已被禁用」（修存在性泄露）
        SysUser user = user(10L, 0);
        when(userService.getByTenantAndUsername(1L, "alice")).thenReturn(user);
        when(passwordEncoder.matches(eq("bad"), anyString())).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("alice", "bad", TENANT_CODE)));

        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        // 失败计入防爆破计数
        verify(authSessionService).recordLoginFailure(1L, "alice");
    }

    @Test
    void disabledUserWithCorrectPasswordGetsDisabledMessage() {
        // 密码正确 + 账号禁用：面向已证明身份的本人提示禁用（合法），且不计入爆破失败
        SysUser user = user(10L, 0);
        when(userService.getByTenantAndUsername(1L, "alice")).thenReturn(user);
        when(passwordEncoder.matches(eq("good"), anyString())).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("alice", "good", TENANT_CODE)));

        assertTrue(ex.getMessage().contains("已被禁用"));
        verify(authSessionService, never()).recordLoginFailure(anyLong(), anyString());
    }

    @Test
    void unknownUserBurnsBcryptCompareAndRecordsFailure() {
        when(userService.getByTenantAndUsername(1L, "ghost")).thenReturn(null);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("ghost", "pwd", TENANT_CODE)));

        // 与已知用户错误文案完全一致，防枚举
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        // 固定哈希同价比对执行（抹平时序差）：DUMMY 哈希非真实用户哈希
        verify(passwordEncoder).matches(eq("pwd"), anyString());
        verify(authSessionService).recordLoginFailure(1L, "ghost");
    }

    @Test
    void successfulLoginClearsFailureCount() {
        SysUser user = user(10L, 1);
        user.setUsername("alice");
        user.setTenantId(1L);
        when(userService.getByTenantAndUsername(1L, "alice")).thenReturn(user);
        when(passwordEncoder.matches(eq("good"), anyString())).thenReturn(true);
        when(userRoleService.listRoleIdsByUser(10L)).thenReturn(List.of());
        when(permissionService.listPermCodesByUser(10L)).thenReturn(java.util.Set.of());
        when(jwtProperties.getExpireHours()).thenReturn(2L);
        when(jwtTokenProvider.createToken(anyLong(), anyLong(), anyString(), any())).thenReturn("token");

        LoginVO vo = authService.login(new LoginRequest("alice", "good", TENANT_CODE));

        assertEquals("token", vo.token());
        verify(authSessionService).clearLoginFailures(1L, "alice");
        verify(authSessionService, never()).recordLoginFailure(anyLong(), anyString());
    }

    private SysUser user(long id, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(status);
        // 非空库内哈希：mock anyString() 不匹配 null，缺省会误触发「密码错误」分支
        user.setPassword("$2a$10$stubhashstubhashstubhashstubhashstubhashstubby");
        return user;
    }
}
