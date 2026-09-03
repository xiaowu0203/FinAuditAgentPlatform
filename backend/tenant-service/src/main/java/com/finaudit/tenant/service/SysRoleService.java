package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.event.UserAuthChangedEvent;
import com.finaudit.tenant.mapper.SysRoleMapper;
import com.finaudit.tenant.pojo.dto.RoleCreateRequest;
import com.finaudit.tenant.pojo.dto.RoleUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysRole;
import com.finaudit.tenant.pojo.vo.RoleVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 角色服务：角色实体（sys_role）的所有查询与更新均收敛于此。
 * <p>删除角色时同步清理用户-角色 / 角色-权限映射（防孤儿行使权限，P3.5a 堵洞），
 * 并刷新受影响用户权限快照。</p>
 */
@Service
public class SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysUserRoleService userRoleService;
    private final SysPermissionService permissionService;
    private final ApplicationEventPublisher eventPublisher;

    public SysRoleService(SysRoleMapper roleMapper, SysUserRoleService userRoleService,
                          SysPermissionService permissionService, ApplicationEventPublisher eventPublisher) {
        this.roleMapper = roleMapper;
        this.userRoleService = userRoleService;
        this.permissionService = permissionService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public RoleVO create(RoleCreateRequest request, Long tenantId) {
        // 查看角色是否已存在
        Long exists = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getTenantId, tenantId)
                .eq(SysRole::getRoleCode, request.roleCode()));
        if (exists > 0) {
            throw new BizException("角色编码已存在: " + request.roleCode());
        }
        SysRole role = SysRole.from(request, tenantId);
        // 新增
        roleMapper.insert(role);
        return RoleVO.from(role);
    }

    @Transactional
    public RoleVO update(Long id, RoleUpdateRequest request) {
        // 获取角色信息，不存在则抛出异常
        SysRole role = getRequired(id);
        // 更新
        role.apply(request);
        roleMapper.updateById(role);
        return RoleVO.from(role);
    }

    /**
     * 删除角色：先记下受影响用户，再删角色本体与两类映射（用户-角色 / 角色-权限）。
     * <p>P3.5a 堵洞：原实现只删角色本体，残留的 sys_user_role/sys_role_permission 孤儿行
     * 会让已删角色的权限继续经快照解析生效。</p>
     */
    @Transactional
    public void delete(Long id) {
        // 获取角色信息，不存在则抛出异常
        getRequired(id);
        // 受影响用户（删映射前先取，事务提交后刷新其快照）
        List<Long> userIds = userRoleService.listUserIdsByRole(id);
        // 删除角色本体（逻辑删）
        roleMapper.deleteById(id);
        // 清理两类映射（sys_user_role / sys_role_permission 均为物理删除语义）
        userRoleService.deleteByRole(id);
        permissionService.deleteByRole(id);
        // 角色没了 → 绑定用户的角色/权限集变化，刷新快照
        userIds.forEach(userId -> eventPublisher.publishEvent(new UserAuthChangedEvent(userId)));
    }

    public RoleVO get(Long id) {
        return RoleVO.from(getRequired(id));
    }

    /** 当前租户全部角色（角色选择器用）。 */
    public List<RoleVO> listByTenant(Long tenantId) {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getTenantId, tenantId)
                        .orderByAsc(SysRole::getId))
                .stream().map(RoleVO::from).toList();
    }

    /** 按 ID 批量查角色（多租户拦截器自动限制在当前租户）。 */
    public List<SysRole> getByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getId, ids));
    }

    public SysRole getRequired(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException("角色不存在: " + id);
        }
        return role;
    }
}
