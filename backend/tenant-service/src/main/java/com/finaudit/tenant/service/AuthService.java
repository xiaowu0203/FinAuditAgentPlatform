package com.finaudit.tenant.service;

import com.finaudit.starter.jwt.AuthSnapshot;
import com.finaudit.starter.jwt.JwtProperties;
import com.finaudit.starter.jwt.JwtTokenProvider;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.tenant.event.RolePermsChangedEvent;
import com.finaudit.tenant.event.UserAuthChangedEvent;
import com.finaudit.tenant.pojo.dto.LoginRequest;
import com.finaudit.tenant.pojo.entity.SysRole;
import com.finaudit.tenant.pojo.entity.SysTenant;
import com.finaudit.tenant.pojo.entity.SysUser;
import com.finaudit.tenant.pojo.vo.LoginVO;
import com.finaudit.tenant.pojo.vo.UserInfoVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;

/**
 * 认证业务服务
 * 负责用户登录、获取个人信息、登出会话作废、用户权限快照写入与刷新（P3.5 实时生效）。
 * <p>登录流程：租户校验 → 用户密码校验 → 角色/权限组装 → 签发 JWT + 写 Redis 权限快照。
 * 快照为网关注入角色/权限头的<b>权威来源</b>；角色绑定、角色权限分配、用户/部门变更经
 * {@link UserAuthChangedEvent}/{@link RolePermsChangedEvent} 在事务提交后刷新快照——
 * 事件解耦避免 SysUserService/SysRoleService 反向依赖本类成环（CLAUDE.md 规范 12）。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SysTenantService tenantService;
    private final SysUserService userService;
    private final SysUserRoleService userRoleService;
    private final SysRoleService roleService;
    private final SysPermissionService permissionService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final AuthSessionService authSessionService;

    public AuthService(SysTenantService tenantService, SysUserService userService,
                       SysUserRoleService userRoleService, SysRoleService roleService,
                       SysPermissionService permissionService,
                       PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties, AuthSessionService authSessionService) {
        this.tenantService = tenantService;
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.permissionService = permissionService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.authSessionService = authSessionService;
    }

    /**
     * 用户登录接口
     * 流程：解析租户编码 → 校验租户状态 → 在指定租户上下文内校验用户密码 → 查询用户角色/权限
     * → 签发JWT + 写权限快照 → 返回登录VO（含角色与权限标识符）
     * @param request 登录请求：tenantCode租户编码、username用户名、password密码
     * @return LoginVO 返回accessToken、token类型、过期秒数、用户信息
     */
    public LoginVO login(LoginRequest request) {
        // 租户编码为空则使用默认租户default
        String tenantCode = (request.tenantCode() == null || request.tenantCode().isBlank())
                ? "default"
                : request.tenantCode().trim();

        // 根据租户编码查询租户
        SysTenant tenant = tenantService.getByCode(tenantCode);
        if (tenant == null) {
            throw new BizException("租户不存在: " + tenantCode);
        }

        // 校验租户是否启用，状态1代表启用
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new BizException("租户已禁用: " + tenantCode);
        }

        /**
         * 登录接口请求没有网关透传的X‑Tenant‑Id请求头，手动设置租户上下文执行登录逻辑
         * TenantContextHolder.runWithResult：在指定租户上下文执行代码块，finally自动清理上下文
         * 上下文生效后，MyBatis‑Plus多租户插件会自动过滤sys_user、sys_user_role、sys_role等租户表数据
         */
        return TenantContextHolder.runWithResult(tenant.getId(), () -> {
            // 在当前租户下查询用户
            SysUser user = userService.getByTenantAndUsername(tenant.getId(), request.username());
            if (user == null) {
                throw new BizException("用户名或密码错误");
            }
            // 校验用户状态，状态1代表启用
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new BizException("用户已被禁用");
            }
            // 密码比对，不匹配抛业务异常
            if (!passwordEncoder.matches(request.password(), user.getPassword())) {
                throw new BizException("用户名或密码错误");
            }
            // 获取用户角色编码集合与权限标识符集合
            List<String> roles = resolveRoleCodes(user.getId());
            Set<String> perms = permissionService.listPermCodesByUser(user.getId());
            // 签发JWT accessToken，内部生成jti用于后续会话吊销（roles 仅作快照缺失时的降级兜底）
            String token = jwtTokenProvider.createToken(user.getId(), user.getTenantId(), user.getUsername(), roles);
            // 写 Redis 权限快照（网关权威来源）：角色/权限/部门 变更实时生效的基准
            authSessionService.writeSnapshot(user.getId(),
                    AuthSnapshot.of(roles, List.copyOf(perms), null, user.getStatus()));
            // token过期时间，单位秒
            long expiresIn = jwtProperties.getExpireHours() * 3600;
            return new LoginVO(token, "Bearer", expiresIn, UserInfoVO.from(user, roles, perms));
        });
    }

    /**
     * 获取当前登录用户信息
     * 网关鉴权过滤器解析JWT后向下游透传X‑User‑Id请求头，控制器取出userId传入此方法
     * @param userId 当前登录用户ID
     * @return UserInfoVO 用户基础信息+角色编码列表+权限标识符列表
     */
    public UserInfoVO me(Long userId) {
        SysUser user = userService.getRequired(userId);
        return UserInfoVO.from(user, resolveRoleCodes(userId), permissionService.listPermCodesByUser(userId));
    }

    /**
     * 用户登出，吊销当前令牌
     * @param jti JWT唯一标识，由网关从JWT载荷提取，通过自定义请求头透传给下游
     * <p>将jti写入Redis黑名单；网关鉴权过滤器校验黑名单，命中直接返回401，实现token立即失效</p>
     */
    public void logout(String jti) {
        authSessionService.revoke(jti);
    }

    /**
     * 用户身份变更（角色绑定/用户信息/删除）→ 事务提交后重写该用户权限快照。
     * <p>fallbackExecution=true：发布方无事务时也执行（容错）。用户已删除则清理快照 key。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAuthChanged(UserAuthChangedEvent event) {
        refreshSnapshot(event.userId());
    }

    /**
     * 角色权限分配变更 → 事务提交后反查该角色全部用户，逐个重写权限快照（在线用户即时生效）。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRolePermsChanged(RolePermsChangedEvent event) {
        for (Long userId : userRoleService.listUserIdsByRole(event.roleId())) {
            refreshSnapshot(userId);
        }
    }

    /**
     * 重写单个用户的权限快照（读最新 DB 状态：用户 + 角色 + 权限）。
     * 用户不存在（已删除）→ 删除快照 key（踢下线由 blackver 机制兜底）。
     */
    private void refreshSnapshot(Long userId) {
        try {
            SysUser user = userService.getById(userId);
            if (user == null) {
                authSessionService.deleteSnapshot(userId);
                return;
            }
            List<String> roles = resolveRoleCodes(userId);
            Set<String> perms = permissionService.listPermCodesByUser(userId);
            authSessionService.writeSnapshot(userId,
                    AuthSnapshot.of(roles, List.copyOf(perms), null, user.getStatus()));
        } catch (Exception e) {
            // 快照刷新失败不影响业务事务结果，打日志即可：最坏情况降级到 JWT 角色（权限置空）
            log.warn("权限快照刷新失败，将降级: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 根据用户ID，解析该用户的角色编码列表
     * @param userId 用户ID
     * @return 角色code集合，用于存入JWT载荷与权限快照
     */
    private List<String> resolveRoleCodes(Long userId) {
        // 查询用户‑角色关联表拿到角色ID，再查询角色表映射为角色编码
        return roleService.getByIds(userRoleService.listRoleIdsByUser(userId))
                .stream().map(SysRole::getRoleCode).toList();
    }
}
