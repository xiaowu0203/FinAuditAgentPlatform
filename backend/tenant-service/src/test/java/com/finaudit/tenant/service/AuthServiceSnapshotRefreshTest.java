package com.finaudit.tenant.service;

import com.finaudit.starter.jwt.AuthSnapshot;
import com.finaudit.starter.jwt.JwtProperties;
import com.finaudit.starter.jwt.JwtTokenProvider;
import com.finaudit.tenant.event.RolePermsChangedEvent;
import com.finaudit.tenant.event.UserAuthChangedEvent;
import com.finaudit.tenant.pojo.entity.SysRole;
import com.finaudit.tenant.pojo.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限快照刷新链单测（P3.5 R1）：
 * 用户身份变更 / 角色权限分配变更事件 → 事务提交后重写 Redis 权限快照——在线用户
 * 下一请求即生效（不经重新登录）；刷新失败静默降级，不影响业务事务结果。
 */
class AuthServiceSnapshotRefreshTest {

    private final SysTenantService tenantService = mock(SysTenantService.class);
    private final SysUserService userService = mock(SysUserService.class);
    private final SysUserRoleService userRoleService = mock(SysUserRoleService.class);
    private final SysRoleService roleService = mock(SysRoleService.class);
    private final SysPermissionService permissionService = mock(SysPermissionService.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);

    private final AuthService authService = new AuthService(
            tenantService, userService, userRoleService, roleService, permissionService,
            mock(PasswordEncoder.class), mock(JwtTokenProvider.class), mock(JwtProperties.class),
            authSessionService);

    private SysUser user(long id, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(status);
        return user;
    }

    private SysRole role(long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        return role;
    }

    /** 组装角色解析：用户→角色ID，角色ID→角色实体（与 AuthService.resolveRoleCodes 调用链一致）。 */
    private void stubRoleResolution(List<Long> roleIds, List<SysRole> roles) {
        when(userRoleService.listRoleIdsByUser(anyLong())).thenReturn(roleIds);
        when(roleService.getByIds(anyCollection())).thenReturn(roles);
    }

    @Test
    void userAuthChanged_rebuildsSnapshotFromDb() {
        when(userService.getById(7L)).thenReturn(user(7, 1));
        stubRoleResolution(List.of(1L, 2L), List.of(role(1, "admin"), role(2, "auditor")));
        when(permissionService.listPermCodesByUser(7L))
                .thenReturn(Set.of("user:create", "audit:approve"));

        authService.onUserAuthChanged(new UserAuthChangedEvent(7L));

        verify(authSessionService).writeSnapshot(eq(7L), argThat(snapshot ->
                snapshot.roles().equals(List.of("admin", "auditor"))
                        && snapshot.perms().containsAll(List.of("user:create", "audit:approve"))
                        && snapshot.deptId() == null
                        && snapshot.status() == 1));
    }

    @Test
    void rolePermsChanged_refreshesEveryUserOfRole() {
        when(userRoleService.listUserIdsByRole(3L)).thenReturn(List.of(7L, 8L));
        when(userService.getById(7L)).thenReturn(user(7, 1));
        when(userService.getById(8L)).thenReturn(user(8, 1));
        stubRoleResolution(List.of(3L), List.of(role(3, "auditor")));
        when(permissionService.listPermCodesByUser(anyLong())).thenReturn(Set.of("audit:viewAll"));

        authService.onRolePermsChanged(new RolePermsChangedEvent(3L));

        verify(userRoleService).listUserIdsByRole(3L);
        verify(authSessionService).writeSnapshot(eq(7L), any(AuthSnapshot.class));
        verify(authSessionService).writeSnapshot(eq(8L), any(AuthSnapshot.class));
        verify(authSessionService, times(2)).writeSnapshot(anyLong(), any(AuthSnapshot.class));
    }

    @Test
    void deletedUser_clearsSnapshotKey() {
        when(userService.getById(99L)).thenReturn(null);

        authService.onUserAuthChanged(new UserAuthChangedEvent(99L));

        verify(authSessionService).deleteSnapshot(99L);
        verify(authSessionService, never()).writeSnapshot(anyLong(), any(AuthSnapshot.class));
    }

    @Test
    void refreshFailure_degradesWithoutPropagating() {
        when(userService.getById(7L)).thenReturn(user(7, 1));
        stubRoleResolution(List.of(), List.of());
        when(permissionService.listPermCodesByUser(7L)).thenReturn(Set.of());
        doThrow(new RuntimeException("redis down")).when(authSessionService).writeSnapshot(anyLong(), any());

        // 快照刷新失败不应影响业务事务结果：静默降级（最坏情况 JWT 角色 + 权限置空）
        assertThatCode(() -> authService.onUserAuthChanged(new UserAuthChangedEvent(7L)))
                .doesNotThrowAnyException();
    }
}