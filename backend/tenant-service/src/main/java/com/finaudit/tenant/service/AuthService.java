package com.finaudit.tenant.service;

import com.finaudit.starter.jwt.JwtProperties;
import com.finaudit.starter.jwt.JwtTokenProvider;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.finaudit.tenant.pojo.dto.LoginRequest;
import com.finaudit.tenant.pojo.entity.SysRole;
import com.finaudit.tenant.pojo.entity.SysTenant;
import com.finaudit.tenant.pojo.entity.SysUser;
import com.finaudit.tenant.pojo.vo.LoginVO;
import com.finaudit.tenant.pojo.vo.UserInfoVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 认证业务服务
 * 负责用户登录、获取个人信息、登出会话作废；
 * 登录流程包含租户校验、用户密码校验、角色组装、签发JWT；
 * 登出调用会话服务将jti加入Redis黑名单实现令牌即时吊销。
 */
@Service
public class AuthService {

    private final SysTenantService tenantService;
    private final SysUserService userService;
    private final SysUserRoleService userRoleService;
    private final SysRoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final AuthSessionService authSessionService;

    public AuthService(SysTenantService tenantService, SysUserService userService,
                       SysUserRoleService userRoleService, SysRoleService roleService,
                       PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties, AuthSessionService authSessionService) {
        this.tenantService = tenantService;
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.authSessionService = authSessionService;
    }

    /**
     * 用户登录接口
     * 流程：解析租户编码 → 校验租户状态 → 在指定租户上下文内校验用户密码 → 查询用户角色 → 签发JWT返回登录VO
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
            // 获取用户角色编码集合
            List<String> roles = resolveRoleCodes(user.getId());
            // 签发JWT accessToken，内部生成jti用于后续会话吊销
            String token = jwtTokenProvider.createToken(user.getId(), user.getTenantId(), user.getUsername(), roles);
            // token过期时间，单位秒
            long expiresIn = jwtProperties.getExpireHours() * 3600;
            return new LoginVO(token, "Bearer", expiresIn, UserInfoVO.from(user, roles));
        });
    }

    /**
     * 获取当前登录用户信息
     * 网关鉴权过滤器解析JWT后向下游透传X‑User‑Id请求头，控制器取出userId传入此方法
     * @param userId 当前登录用户ID
     * @return UserInfoVO 用户基础信息+角色编码列表
     */
    public UserInfoVO me(Long userId) {
        SysUser user = userService.getRequired(userId);
        return UserInfoVO.from(user, resolveRoleCodes(userId));
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
     * 根据用户ID，解析该用户的角色编码列表
     * @param userId 用户ID
     * @return 角色code集合，用于存入JWT载荷
     */
    private List<String> resolveRoleCodes(Long userId) {
        // 查询用户‑角色关联表拿到角色ID，再查询角色表映射为角色编码
        return roleService.getByIds(userRoleService.listRoleIdsByUser(userId))
                .stream().map(SysRole::getRoleCode).toList();
    }
}
