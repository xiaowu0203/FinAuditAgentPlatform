package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.event.RolePermsChangedEvent;
import com.finaudit.tenant.mapper.SysPermissionMapper;
import com.finaudit.tenant.mapper.SysRolePermissionMapper;
import com.finaudit.tenant.pojo.entity.SysPermission;
import com.finaudit.tenant.pojo.entity.SysRolePermission;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限服务：权限目录（sys_permission，平台级全局）与角色-权限映射（sys_role_permission）的
 * 查询、用户权限解析、替换式分配均收敛于此。
 * <p>sys_permission 已注册多租户拦截器忽略名单（无 tenant_id）；sys_role_permission 为租户表，
 * 查询自动按上下文过滤。分配变更经 {@link RolePermsChangedEvent} 触发在线用户权限快照刷新。</p>
 */
@Service
public class SysPermissionService {

    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleService userRoleService;
    private final ApplicationEventPublisher eventPublisher;

    public SysPermissionService(SysPermissionMapper permissionMapper,
                                SysRolePermissionMapper rolePermissionMapper,
                                SysUserRoleService userRoleService,
                                ApplicationEventPublisher eventPublisher) {
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleService = userRoleService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 权限目录（启用状态，按分组+ID 排序）：角色分配界面与前端权限元数据用。
     */
    public List<SysPermission> listCatalog() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getStatus, 1)
                .orderByAsc(SysPermission::getGroupName)
                .orderByAsc(SysPermission::getId));
    }

    /**
     * 解析用户的权限标识符集合（登录写快照 / 快照刷新用）：
     * 用户 → 角色 → 角色权限映射 → 启用的权限码（去重）。
     * <p>sys_role_permission 按租户过滤、sys_permission 全局无租户——跨租户角色互不可见。</p>
     */
    public Set<String> listPermCodesByUser(Long userId) {
        List<Long> roleIds = userRoleService.listRoleIdsByUser(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<Long> permIds = listPermIdsByRoles(roleIds);
        return new LinkedHashSet<>(listPermCodesByIds(permIds));
    }

    /** 角色已分配的权限 ID 列表（GET 角色权限回显用）。 */
    public List<Long> listPermIdsByRole(Long roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream().map(SysRolePermission::getPermId).toList();
    }

    /** 按角色物理删映射（P3.5a 删除角色时清孤儿行，防已删角色权限残留）。 */
    public void deleteByRole(Long roleId) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
    }

    /**
     * 替换式分配角色权限：物理删旧行（映射表无 @TableLogic）后批量插入，空列表即清空。
     * <p>分配入参先校验权限 ID 全部存在于目录（防越权伪造 perm_id）；
     * 提交后发 {@link RolePermsChangedEvent} 刷新该角色在线用户快照。</p>
     */
    @Transactional
    public void replaceRolePermissions(Long roleId, Long tenantId, List<Long> permIds) {
        validatePermIds(permIds);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        List<SysRolePermission> rows = SysRolePermission.listFrom(tenantId, roleId, permIds);
        if (!rows.isEmpty()) {
            rolePermissionMapper.insertBatch(rows);
        }
        eventPublisher.publishEvent(new RolePermsChangedEvent(roleId));
    }

    /** 校验权限 ID 全部存在于启用目录（防伪造 perm_id 越权写映射）。 */
    private void validatePermIds(List<Long> permIds) {
        if (permIds == null || permIds.isEmpty()) {
            return;
        }
        List<SysPermission> found = listByIds(permIds);
        if (found.size() != permIds.stream().filter(java.util.Objects::nonNull).distinct().count()) {
            throw new BizException("存在无效的权限ID");
        }
    }

    private List<Long> listPermIdsByRoles(List<Long> roleIds) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .in(SysRolePermission::getRoleId, roleIds))
                .stream().map(SysRolePermission::getPermId).toList();
    }

    private List<String> listPermCodesByIds(List<Long> permIds) {
        if (permIds.isEmpty()) {
            return List.of();
        }
        return listByIds(permIds).stream().map(SysPermission::getPermCode).toList();
    }

    /** 批量按 ID 查启用权限（先判空，避免非法 IN ()）。 */
    private List<SysPermission> listByIds(List<Long> permIds) {
        if (permIds == null || permIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .in(SysPermission::getId, permIds)
                .eq(SysPermission::getStatus, 1));
    }
}
