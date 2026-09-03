package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.event.UserAuthChangedEvent;
import com.finaudit.tenant.mapper.SysUserMapper;
import com.finaudit.tenant.pojo.dto.UserCreateRequest;
import com.finaudit.tenant.pojo.dto.UserUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysUser;
import com.finaudit.tenant.pojo.vo.RoleVO;
import com.finaudit.tenant.pojo.vo.UserDetailVO;
import com.finaudit.tenant.pojo.vo.UserVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户服务：用户实体（sys_user）的所有查询与更新均收敛于此。
 * <p>角色绑定等跨实体数据经 {@link SysUserRoleService} / {@link SysRoleService} 委托，不直接触碰关联 Mapper。
 * 用户/角色绑定变更后发 {@link UserAuthChangedEvent}（事务提交后由 AuthService 重写权限快照，实时生效）。</p>
 */
@Service
public class SysUserService {

    private final SysUserMapper userMapper;
    /** 用户角色关联服务，维护用户‑角色多对多关系 */
    private final SysUserRoleService userRoleService;
    /** 角色服务，查询角色基础信息 */
    private final SysRoleService roleService;
    /** 部门服务，用于部门存在性校验、获取部门名称 */
    private final SysDeptService deptService;

    /** 密码加密器，用户密码BCrypt加密 */
    private final PasswordEncoder passwordEncoder;
    /** 会话服务，管理用户Token会话，实现踢下线、会话作废 */
    private final AuthSessionService authSessionService;
    /** Spring事件发布器，发布用户权限变更事件，用于刷新权限快照缓存 */
    private final ApplicationEventPublisher eventPublisher;

    public SysUserService(SysUserMapper userMapper, SysUserRoleService userRoleService,
                          SysRoleService roleService, SysDeptService deptService,
                          PasswordEncoder passwordEncoder,
                          AuthSessionService authSessionService, ApplicationEventPublisher eventPublisher) {
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.deptService = deptService;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 创建系统用户
     * <p>事务：用户名唯一性校验 → 部门合法性校验 → 用户入库 → 绑定角色 → 发布权限变更事件刷新快照</p>
     * @param request 创建用户请求参数
     * @param tenantId 当前租户ID
     * @return 用户VO视图对象
     * @throws BizException 用户名已存在、部门非法抛出业务异常
     */
    @Transactional
    public UserVO create(UserCreateRequest request, Long tenantId) {
        // 查询是否已经存在账户
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUsername, request.username()));
        // 若已存在则直接抛出异常
        if (exists > 0) {
            throw new BizException("用户名已存在: " + request.username());
        }
        // 校验部门归属（租户内存在性，防虚构/跨租户）
        validateDept(request.deptId());
        // 创建用户
        SysUser user = SysUser.from(request, tenantId, passwordEncoder.encode(request.password()));
        // 落库
        userMapper.insert(user);

        // 若用户角色不为空，则直接进行绑定
        if (request.roleIds() != null) {
            // 绑定角色
            userRoleService.replaceRoles(user.getId(), tenantId, request.roleIds());
        }
        // 触发权限快照写入（新用户初始快照，等价登录时写入）
        eventPublisher.publishEvent(new UserAuthChangedEvent(user.getId()));
        return UserVO.from(user, deptNameOf(user));
    }

    /**
     * 更新用户信息
     * <p>事务：更新用户基础信息；如果禁用用户，主动作废全部会话；发布事件刷新权限快照</p>
     * @param id 用户ID
     * @param request 用户更新请求参数
     * @param tenantId 当前租户ID
     * @return 更新后的用户VO
     * @throws BizException 用户不存在、部门非法抛出异常
     */
    @Transactional
    public UserVO update(Long id, UserUpdateRequest request, Long tenantId) {
        // 校验用户
        SysUser user = getRequired(id);
        // 校验部门归属（0=解绑跳过；null=不改）
        if (request.deptId() != null && request.deptId() != 0L) {
            validateDept(request.deptId());
        }
        // 校验密码
        String encoded = StringUtils.hasText(request.password())
                ? passwordEncoder.encode(request.password())
                : null;
        // 应用最新数据并落库
        user.apply(request, encoded);
        userMapper.updateById(user);
        // 若是禁用用户 → 则升级会话版本号，踢掉该用户所有已签发 token
        if (request.status() != null && request.status() == 0) {
            authSessionService.revokeAll(id);
        }
        // 用户信息变更（部门/状态等）→ 事务提交后刷新权限快照
        eventPublisher.publishEvent(new UserAuthChangedEvent(id));
        return UserVO.from(user, deptNameOf(user));
    }

    /**
     * 给用户分配角色（替换模式，覆盖原有角色）
     * @param id 用户ID
     * @param tenantId 租户ID
     * @param roleIds 角色ID集合
     */
    @Transactional
    public void assignRoles(Long id, Long tenantId, List<Long> roleIds) {
        // 校验账户是否存在
        getRequired(id);
        // 更新用户角色
        userRoleService.replaceRoles(id, tenantId, roleIds);
        // 角色绑定变更 → 事务提交后刷新权限快照（在线用户无需重登即生效）
        eventPublisher.publishEvent(new UserAuthChangedEvent(id));
    }

    /**
     * 删除用户
     * <p>事务：删除用户记录 → 作废全部会话 → 发布事件清理权限快照</p>
     * @param id 用户ID
     */
    @Transactional
    public void delete(Long id) {
        // 校验用户是否存在
        getRequired(id);
        // 删除用户
        userMapper.deleteById(id);
        // 用户被删除 → 同步踢掉其全部会话
        authSessionService.revokeAll(id);
        // 删除后清权限快照（监听方发现用户不存在时清 key）
        eventPublisher.publishEvent(new UserAuthChangedEvent(id));
    }

    /**
     * 获取用户详情，附带已分配角色列表
     * @param id 用户ID
     * @return 用户详情VO，包含角色集合、部门名称
     */
    public UserDetailVO detail(Long id) {
        SysUser user = getRequired(id);
        List<RoleVO> roles = roleService.getByIds(userRoleService.listRoleIdsByUser(id))
                .stream().map(RoleVO::from).toList();
        return UserDetailVO.from(user, roles, deptNameOf(user));
    }

    /**
     * 用户分页查询，支持关键词模糊搜索；批量查询部门名称，规避N+1查询问题
     * @param pageNum 页码
     * @param pageSize 页大小
     * @param keyword 搜索关键词：用户名/真实姓名/手机号
     * @return 分页VO结果
     */
    public Page<UserVO> page(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        wrapper.orderByDesc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        // 批量获取本页所有用户对应的部门名称，避免循环内单条查询产生N+1
        Map<Long, String> deptNames = deptService.mapDeptNameByIds(
                page.getRecords().stream().map(SysUser::getDeptId)
                        .filter(java.util.Objects::nonNull).distinct().toList());
        // 组装VO分页对象
        Page<UserVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(u -> UserVO.from(u, u.getDeptId() == null ? null : deptNames.get(u.getDeptId())))
                .toList());
        return voPage;
    }

    /**
     * 统计绑定指定部门下的用户数量
     * <p>用于部门删除前置校验：部门下存在用户禁止删除，作为引用守卫</p>
     * @param deptId 部门ID
     * @return 该部门下用户总数
     */
    public long countByDept(Long deptId) {
        return userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeptId, deptId));
    }

    /**
     * 部门ID合法性校验
     * <p>null / 0 跳过校验；其他ID校验部门真实存在，防止绑定虚构、跨租户部门</p>
     * @param deptId 待校验部门ID
     * @throws BizException 部门不存在抛出异常
     */
    private void validateDept(Long deptId) {
        if (deptId != null && deptId != 0L) {
            deptService.getRequired(deptId);
        }
    }

    /**
     * 获取用户所属部门名称
     * @param user 用户实体
     * @return 部门名称，未绑定部门返回null
     */
    private String deptNameOf(SysUser user) {
        if (user.getDeptId() == null) {
            return null;
        }
        return deptService.mapDeptNameByIds(List.of(user.getDeptId())).get(user.getDeptId());
    }

    /**
     * 根据ID获取用户，不存在直接抛业务异常
     * @param id 用户ID
     * @return 用户实体
     * @throws BizException 用户不存在抛出异常
     */
    public SysUser getRequired(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("用户不存在: " + id);
        }
        return user;
    }

    /**
     * 根据ID查询用户，允许返回null
     * <p>用于权限快照刷新场景：用户已删除不抛异常，上层感知null做清理</p>
     * @param id 用户ID
     * @return 用户实体 / null
     */
    public SysUser getById(Long id) {
        return userMapper.selectById(id);
    }

    /**
     * 根据租户ID + 用户名查询用户，登录接口使用
     * <p>多租户插件会自动追加租户上下文过滤条件，双重保障租户隔离</p>
     * @param tenantId 租户ID
     * @param username 用户名
     * @return 用户实体
     */
    public SysUser getByTenantAndUsername(Long tenantId, String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUsername, username));
    }
}
