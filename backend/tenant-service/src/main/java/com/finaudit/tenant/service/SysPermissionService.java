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
 * 权限业务服务
 * <p>维护权限元数据、角色‑权限多对多映射；支持角色权限替换分配；提供用户权限码计算，用于构建AuthSnapshot权限快照</p>
 * <p>数据说明：sys_permission权限表全局共享无租户；sys_role_permission角色权限关联表带租户隔离；角色变更发布{@link RolePermsChangedEvent}事件触发用户权限快照刷新</p>
 */
@Service
public class SysPermissionService {

    /** 权限元数据Mapper，存储权限编码、分组、状态 */
    private final SysPermissionMapper permissionMapper;
    /** 角色‑权限关联Mapper，维护多对多关系，带租户字段 */
    private final SysRolePermissionMapper rolePermissionMapper;
    /** 用户‑角色关联服务，查询用户所拥有角色ID */
    private final SysUserRoleService userRoleService;
    /** Spring事件发布器，发布角色权限变更事件 */
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
     * 查询启用状态的权限目录
     * <p>用于角色分配页面、前端获取全部可用权限元数据；按分组名称、ID升序排序</p>
     * @return 全部启用权限列表
     */
    public List<SysPermission> listCatalog() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getStatus, 1)
                .orderByAsc(SysPermission::getGroupName)
                .orderByAsc(SysPermission::getId));
    }

    /**
     * 解析用户最终权限标识符集合
     * <p>链路：用户ID → 用户角色ID列表 → 角色关联权限ID → 过滤启用权限，提取权限编码并去重</p>
     * <p>用于登录、权限快照刷新；sys_role_permission做租户隔离；sys_permission为全局公共元数据</p>
     * @param userId 用户ID
     * @return 用户权限编码集合，空返回空集合，使用LinkedHashSet保留顺序并去重
     */
    public Set<String> listPermCodesByUser(Long userId) {
        List<Long> roleIds = userRoleService.listRoleIdsByUser(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<Long> permIds = listPermIdsByRoles(roleIds);
        return new LinkedHashSet<>(listPermCodesByIds(permIds));
    }

    /**
     * 获取单个角色已分配的权限ID列表
     * <p>角色编辑页面回显已勾选权限使用</p>
     * @param roleId 角色ID
     * @return 该角色绑定的权限ID集合
     */
    public List<Long> listPermIdsByRole(Long roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream().map(SysRolePermission::getPermId).toList();
    }

    /**
     * 根据角色ID物理删除全部角色‑权限关联记录
     * <p>角色删除时调用，清理孤儿关联数据，避免已删除角色残留权限</p>
     * @param roleId 角色ID
     */
    public void deleteByRole(Long roleId) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
    }

    /**
     * 替换模式分配角色权限
     * <p>先校验传入权限ID合法性；物理删除该角色旧关联数据，再批量插入新关联；传入空集合等价清空角色全部权限</p>
     * <p>关联表无逻辑删除，直接物理操作；操作完成发布{@link RolePermsChangedEvent}，刷新该角色下全部在线用户的权限快照</p>
     * @param roleId 角色ID
     * @param tenantId 租户ID
     * @param permIds 待分配权限ID集合
     * @throws BizException 存在无效权限ID抛出业务异常
     */
    @Transactional
    public void replaceRolePermissions(Long roleId, Long tenantId, List<Long> permIds) {
        // 校验权限列表
        validatePermIds(permIds);
        // 删除该角色旧的全部权限映射
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        // 组装批量插入实体
        List<SysRolePermission> rows = SysRolePermission.listFrom(tenantId, roleId, permIds);
        if (!rows.isEmpty()) {
            rolePermissionMapper.insertBatch(rows);
        }
        // 发布角色权限变更事件，触发关联用户AuthSnapshot刷新
        eventPublisher.publishEvent(new RolePermsChangedEvent(roleId));
    }

    /**
     * 校验权限ID全部合法有效
     * <p>校验传入权限ID必须存在并且状态为启用，防止前端伪造非法permId越权写入角色权限映射</p>
     * @param permIds 待校验权限ID列表
     * @throws BizException 包含无效/已停用权限ID抛出异常
     */
    private void validatePermIds(List<Long> permIds) {
        if (permIds == null || permIds.isEmpty()) {
            return;
        }
        List<SysPermission> found = listByIds(permIds);
        // 校验查询到的有效权限数量与去重后的入参数量一致
        if (found.size() != permIds.stream().filter(java.util.Objects::nonNull).distinct().count()) {
            throw new BizException("存在无效的权限ID");
        }
    }

    /**
     * 根据一批角色ID查询所有关联的权限ID
     * @param roleIds 角色ID集合
     * @return 权限ID列表（可能重复，上层做去重）
     */
    private List<Long> listPermIdsByRoles(List<Long> roleIds) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .in(SysRolePermission::getRoleId, roleIds))
                .stream().map(SysRolePermission::getPermId).toList();
    }

    /**
     * 根据权限ID集合获取权限编码
     * @param permIds 权限ID列表
     * @return 权限编码集合
     */
    private List<String> listPermCodesByIds(List<Long> permIds) {
        if (permIds.isEmpty()) {
            return List.of();
        }
        return listByIds(permIds).stream().map(SysPermission::getPermCode).toList();
    }

    /**
     * 批量查询启用状态的权限元数据
     * <p>入参为空直接返回空集合，避免MyBatis IN()传入空列表引发SQL语法异常</p>
     * @param permIds 权限ID集合
     * @return 状态启用的权限实体列表
     */
    private List<SysPermission> listByIds(List<Long> permIds) {
        if (permIds == null || permIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .in(SysPermission::getId, permIds)
                .eq(SysPermission::getStatus, 1));
    }
}
