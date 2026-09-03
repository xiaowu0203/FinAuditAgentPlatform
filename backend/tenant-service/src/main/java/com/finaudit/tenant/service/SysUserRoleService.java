package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.tenant.mapper.SysUserRoleMapper;
import com.finaudit.tenant.pojo.entity.SysUserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 用户-角色关联服务：关联实体（sys_user_role）的所有查询与更新均收敛于此。
 */
@Service
public class SysUserRoleService {

    private final SysUserRoleMapper userRoleMapper;

    public SysUserRoleService(SysUserRoleMapper userRoleMapper) {
        this.userRoleMapper = userRoleMapper;
    }

    /** 按用户查角色 ID 列表。 */
    public List<Long> listRoleIdsByUser(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    /** 按角色查用户 ID 列表（P3.5：角色权限变更后批量刷新这些用户的权限快照）。 */
    public List<Long> listUserIdsByRole(Long roleId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).toList();
    }

    /** 按角色物理删映射（P3.5a 删除角色时清孤儿行，防已删角色权限残留）。 */
    public void deleteByRole(Long roleId) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId));
    }

    /**
     * 替换式绑定角色：删除旧绑定（逻辑删）后批量新增，空列表即清空角色。
     * 多租户拦截器自动按上下文过滤，保证仅操作当前租户数据。
     */
    @Transactional
    public void replaceRoles(Long userId, Long tenantId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<SysUserRole> list = roleIds.stream()
                .filter(Objects::nonNull)
                .map(roleId -> SysUserRole.from(tenantId, userId, roleId))
                .toList();
        if (!list.isEmpty()) {
            userRoleMapper.insertBatch(list);
        }
    }
}
