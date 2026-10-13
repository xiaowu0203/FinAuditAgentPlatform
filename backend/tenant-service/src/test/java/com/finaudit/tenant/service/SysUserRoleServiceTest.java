package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.mapper.SysUserMapper;
import com.finaudit.tenant.mapper.SysUserRoleMapper;
import com.finaudit.tenant.pojo.entity.SysRole;
import com.finaudit.tenant.pojo.entity.SysUser;
import com.finaudit.tenant.pojo.entity.SysUserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色绑定单测（P3.8 R7-2）。
 *
 * <p>两个断言对应两类真实故障：</p>
 * <ol>
 *   <li><b>重复 roleId 撞唯一键</b>：{@code sys_user_role} 有 {@code uk(user_id, role_id)}，
 *       请求体里出现重复 id（前端多选、编排代码拼接都可能）会让整批插入报 400/500；
 *       服务端去重后应正常写入。</li>
 *   <li><b>去重不得误删不同角色</b>：用 distinct 而不是"只留一个"，两个不同角色都要保留。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SysUserRoleServiceTest {

    @Mock
    private SysUserRoleMapper userRoleMapper;

    private SysUserRoleService service() {
        return new SysUserRoleService(userRoleMapper);
    }

    @SuppressWarnings("unchecked")
    private List<Long> capturedRoleIds() {
        ArgumentCaptor<List<SysUserRole>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleMapper).insertBatch(captor.capture());
        return captor.getValue().stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
    }

    @Test
    void duplicateRoleIdsAreDeduplicatedBeforeBatchInsert() {
        when(userRoleMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(userRoleMapper.insertBatch(any())).thenReturn(1);

        service().replaceRoles(5L, 1L, List.of(1L, 1L, 2L, 2L, 2L));

        assertEquals(List.of(1L, 2L), capturedRoleIds(), "重复 roleId 必须去重后再批量插入");
    }

    @Test
    void nullRoleIdsAreFilteredOut() {
        when(userRoleMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(userRoleMapper.insertBatch(any())).thenReturn(1);

        // java.util.List.of 不允许 null，这里用 ArrayList 构造含 null 入参
        List<Long> withNull = new java.util.ArrayList<>();
        withNull.add(1L);
        withNull.add(null);
        service().replaceRoles(5L, 1L, withNull);

        assertEquals(List.of(1L), capturedRoleIds(), "null roleId 应被丢弃");
    }

    @Test
    void emptyListOnlyClearsOldBindings() {
        when(userRoleMapper.delete(any(Wrapper.class))).thenReturn(1);

        service().replaceRoles(5L, 1L, List.of());

        // 清空角色：删除旧绑定，但不调用 insertBatch
        verify(userRoleMapper).delete(any(Wrapper.class));
        org.mockito.Mockito.verify(userRoleMapper, org.mockito.Mockito.never()).insertBatch(any());
    }

    @Test
    void allNullRoleIdsSkipInsert() {
        when(userRoleMapper.delete(any(Wrapper.class))).thenReturn(1);

        List<Long> allNull = new java.util.ArrayList<>();
        allNull.add(null);

        service().replaceRoles(5L, 1L, allNull);

        org.mockito.Mockito.verify(userRoleMapper, org.mockito.Mockito.never()).insertBatch(any());
    }

    /** 防越权：跨租户/不存在的 roleId 必须在 Service 层被拒（P3.8 R7-2） */
    @Test
    void assignRolesRejectsRoleNotOwnedByTenant() {
        SysUserRoleService userRoleService = new SysUserRoleService(userRoleMapper);
        SysRoleService roleService = mock(SysRoleService.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);

        SysUser user = new SysUser();
        user.setId(5L);
        user.setTenantId(1L);
        when(userMapper.selectById(5L)).thenReturn(user);
        // 请求里混进一个不属于本租户的 roleId：按租户过滤后只查得到 1 个角色
        when(roleService.getByIds(any())).thenReturn(List.of(new SysRole()));

        SysUserService userService = new SysUserService(userMapper, userRoleService, roleService,
                mock(SysDeptService.class), mock(PasswordEncoder.class),
                mock(AuthSessionService.class), mock(ApplicationEventPublisher.class));

        assertThrows(BizException.class, () -> userService.assignRoles(5L, 1L, List.of(1L, 999L)),
                "存在无效/跨租户角色时应拒绝绑定");
        // 被拒后不得写入任何绑定
        org.mockito.Mockito.verify(userRoleMapper, org.mockito.Mockito.never()).insertBatch(any());
    }
}
