package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.mapper.SysRoleMapper;
import com.finaudit.tenant.pojo.dto.RoleCreateRequest;
import com.finaudit.tenant.pojo.dto.RoleUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysRole;
import com.finaudit.tenant.pojo.vo.RoleVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 角色服务：角色实体（sys_role）的所有查询与更新均收敛于此。
 */
@Service
public class SysRoleService {

    private final SysRoleMapper roleMapper;

    public SysRoleService(SysRoleMapper roleMapper) {
        this.roleMapper = roleMapper;
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

    @Transactional
    public void delete(Long id) {
        // 获取角色信息，不存在则抛出异常
        getRequired(id);
        // 删除
        roleMapper.deleteById(id);
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
