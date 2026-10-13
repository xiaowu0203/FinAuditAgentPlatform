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
     *
     * <p><b>去重（P3.8 R7-2）</b>：入参去重后再批量插入。{@code sys_user_role} 上有
     * {@code uk(user_id, role_id)}，重复的 roleId 会让整批插入撞唯一键报错——
     * 而调用方（前端多选框/编排代码）给出重复 id 是常见输入，不应升级为 500。
     * 用「服务端去重」而不是 {@code INSERT IGNORE}：语义更明确，且不掩盖真实冲突
     * （真实的跨租户/脏数据仍应暴露）。</p>
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
                .distinct()
                .map(roleId -> SysUserRole.from(tenantId, userId, roleId))
                .toList();
        if (!list.isEmpty()) {
            userRoleMapper.insertBatch(list);
        }
    }
}
