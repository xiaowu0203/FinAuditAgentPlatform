package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.mapper.SysUserMapper;
import com.finaudit.tenant.pojo.dto.UserCreateRequest;
import com.finaudit.tenant.pojo.dto.UserUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysUser;
import com.finaudit.tenant.pojo.vo.RoleVO;
import com.finaudit.tenant.pojo.vo.UserDetailVO;
import com.finaudit.tenant.pojo.vo.UserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户服务：用户实体（sys_user）的所有查询与更新均收敛于此。
 * <p>角色绑定等跨实体数据经 {@link SysUserRoleService} / {@link SysRoleService} 委托，不直接触碰关联 Mapper。</p>
 */
@Service
public class SysUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleService userRoleService;
    private final SysRoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;

    public SysUserService(SysUserMapper userMapper, SysUserRoleService userRoleService,
                          SysRoleService roleService, PasswordEncoder passwordEncoder,
                          AuthSessionService authSessionService) {
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
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
        return UserVO.from(user);
    }

    @Transactional
    public void assignRoles(Long id, Long tenantId, List<Long> roleIds) {
        // 校验账户是否存在
        getRequired(id);
        // 更新用户角色
        userRoleService.replaceRoles(id, tenantId, roleIds);
    }

    @Transactional
    public void delete(Long id) {
        // 校验用户是否存在
        getRequired(id);
        // 删除用户
        userMapper.deleteById(id);
        // 用户被删除 → 同步踢掉其全部会话
        authSessionService.revokeAll(id);
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

    /** 按租户+用户名查询（登录用；多租户拦截器同时按上下文过滤）。 */
    public SysUser getByTenantAndUsername(Long tenantId, String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUsername, username));
    }
}
