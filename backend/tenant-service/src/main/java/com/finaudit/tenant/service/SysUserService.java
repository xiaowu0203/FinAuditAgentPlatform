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

import java.util.List;

/**
 * 用户服务：用户实体（sys_user）的所有查询与更新均收敛于此。
 * <p>角色绑定等跨实体数据经 {@link SysUserRoleService} / {@link SysRoleService} 委托，不直接触碰关联 Mapper。
 * 用户/角色绑定变更后发 {@link UserAuthChangedEvent}（事务提交后由 AuthService 重写权限快照，实时生效）。</p>
 */
@Service
public class SysUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleService userRoleService;
    private final SysRoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final ApplicationEventPublisher eventPublisher;

    public SysUserService(SysUserMapper userMapper, SysUserRoleService userRoleService,
                          SysRoleService roleService, PasswordEncoder passwordEncoder,
                          AuthSessionService authSessionService, ApplicationEventPublisher eventPublisher) {
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
        this.eventPublisher = eventPublisher;
    }

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
        return UserVO.from(user);
    }

    @Transactional
    public UserVO update(Long id, UserUpdateRequest request, Long tenantId) {
        // 校验用户
        SysUser user = getRequired(id);
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
        return UserVO.from(user);
    }

    @Transactional
    public void assignRoles(Long id, Long tenantId, List<Long> roleIds) {
        // 校验账户是否存在
        getRequired(id);
        // 更新用户角色
        userRoleService.replaceRoles(id, tenantId, roleIds);
        // 角色绑定变更 → 事务提交后刷新权限快照（在线用户无需重登即生效）
        eventPublisher.publishEvent(new UserAuthChangedEvent(id));
    }

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

    public UserDetailVO detail(Long id) {
        SysUser user = getRequired(id);
        List<RoleVO> roles = roleService.getByIds(userRoleService.listRoleIdsByUser(id))
                .stream().map(RoleVO::from).toList();
        return UserDetailVO.from(user, roles);
    }

    public Page<UserVO> page(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        wrapper.orderByDesc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<UserVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(UserVO::from).toList());
        return voPage;
    }

    public SysUser getRequired(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("用户不存在: " + id);
        }
        return user;
    }

    /** 按 ID 查用户（可空；快照刷新用——用户已删除返回 null 而非抛异常）。 */
    public SysUser getById(Long id) {
        return userMapper.selectById(id);
    }

    /** 按租户+用户名查询（登录用；多租户拦截器同时按上下文过滤）。 */
    public SysUser getByTenantAndUsername(Long tenantId, String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUsername, username));
    }
}
