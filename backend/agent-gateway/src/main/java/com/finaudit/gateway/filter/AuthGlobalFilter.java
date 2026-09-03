package com.finaudit.gateway.filter;

import com.finaudit.starter.jwt.AuthClaims;
import com.finaudit.starter.jwt.AuthSessionConstants;
import com.finaudit.starter.jwt.AuthSnapshot;
import com.finaudit.starter.jwt.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 网关统一鉴权过滤器
 * 职责：
 * 1. 对白名单接口直接放行，剥离客户端伪造的身份请求头
 * 2. 解析请求头 Bearer JWT token，校验token合法性
 * 3. 校验通过后以 JWT 载荷 + Redis 用户快照注入
 *    {@code X-Tenant-Id / X-User-Id / X-Username / X-User-Roles / X-User-Perms / X-Dept-Id}，向下游微服务透传
 * 4. token校验失败返回401 JSON响应
 * ⚠️安全关键点：客户端传入的 X‑Tenant‑Id / X‑User‑Id 等身份头全部删除，
 * 下游服务只信任网关解析JWT之后重新写入的请求头，防止前端直接伪造身份越权。
 * P3.5：角色/权限以 Redis 快照（finaudit:auth:snapshot:{userId}）为权威——角色/权限变更
 * 无需重新登录即生效；快照缺失时降级：角色用 JWT claims，权限置空（@RequirePerm 端点 fail-closed）。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /**
     * 透传给下游服务的请求头常量，下游微服务读取这些header获取登录用户/租户信息
     */
    // 租户ID
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    // 用户ID
    public static final String HEADER_USER_ID = "X-User-Id";
    // 用户名称
    public static final String HEADER_USERNAME = "X-Username";
    // 用户角色
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    // 用户权限标识符（P3.5，快照权威）
    public static final String HEADER_USER_PERMS = "X-User-Perms";
    // 用户部门ID（P3.5b 部门实体，快照权威）
    public static final String HEADER_DEPT_ID = "X-Dept-Id";
    // JWT 唯一标识（登出/作废时下游据此写黑名单）
    public static final String HEADER_JWT_ID = "X-Jwt-Jti";

    /**
     * 可被客户端伪造的身份头数组
     * 无论白名单还是鉴权接口，网关处理前一律移除，身份只能来源于JWT解析/快照读取结果
     */
    private static final String[] SPOOFABLE_HEADERS = {
            HEADER_TENANT_ID, HEADER_USER_ID, HEADER_USERNAME, HEADER_USER_ROLES,
            HEADER_USER_PERMS, HEADER_DEPT_ID, HEADER_JWT_ID
    };

    /** Redis 会话 key 前缀（与 tenant-service 写黑名单的 key 保持一致） */
    public static final String AUTH_PREFIX = AuthSessionConstants.AUTH_PREFIX;

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    // 响应式 Redis 客户端：每请求做黑名单校验
    private final ReactiveStringRedisTemplate redisTemplate;
    // ant路径匹配器，用于匹配白名单路径
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthGlobalFilter(JwtTokenProvider jwtTokenProvider, ReactiveStringRedisTemplate redisTemplate) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 网关全局过滤器核心逻辑
     * @param exchange 请求上下文，包含request、response
     * @param chain 过滤器链
     * @return Mono<Void> webflux响应
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 获取当前请求信息
        ServerHttpRequest request = exchange.getRequest();
        // 获取请求路径
        String path = request.getURI().getPath();

        // 1. 判断是否白名单接口：不需要token鉴权，只剥离伪造身份头直接放行
        if (isWhitelisted(request, path)) {
            return chain.filter(exchange.mutate()
                    .request(stripSpoofableHeaders(request))
                    .build());
        }

        // 2. 从Authorization请求头提取Bearer token
        String token = resolveBearerToken(request);
        // token为空时抛出鉴权异常
        if (!StringUtils.hasText(token)) {
            return unauthorized(exchange, "缺少 Authorization: Bearer <token>");
        }

        final AuthClaims claims;
        try {
            // 3. 解析、校验JWT，校验签名、过期时间
            claims = jwtTokenProvider.parseToken(token);
        } catch (JwtException e) {
            return unauthorized(exchange, "token 无效或已过期");
        }

        // 4. 校验JWT载荷关键字段不能为空：租户ID、用户ID是业务多租户的核心
        if (claims == null || claims.userId() == null || claims.tenantId() == null) {
            return unauthorized(exchange, "token 载荷缺少用户或租户信息");
        }

        // 5. 会话有效性校验 + 权限快照读取：一次 multiGet 查 3 key
        //    （作废 jti / 用户级作废版本 / 用户权限快照），快照为角色/权限的权威来源
        return resolveSession(claims)
                .flatMap(session -> {
                    if (session.revoked()) {
                        return unauthorized(exchange, "登录已失效，请重新登录");
                    }
                    // 6. 构建新请求：先删除客户端伪造的身份头，再把 JWT + 快照解析出的身份信息写入请求头透传到下游微服务
                    ServerHttpRequest mutated = request.mutate()
                            .headers(headers -> {
                                // 清除客户端带来的身份头，杜绝伪造
                                for (String h : SPOOFABLE_HEADERS) {
                                    headers.remove(h);
                                }
                                // 网关注入可信租户ID、用户ID
                                headers.set(HEADER_TENANT_ID, String.valueOf(claims.tenantId()));
                                headers.set(HEADER_USER_ID, String.valueOf(claims.userId()));
                                // 用户名非空才设置
                                if (StringUtils.hasText(claims.username())) {
                                    headers.set(HEADER_USERNAME, claims.username());
                                }
                                // 角色列表：快照命中以快照为权威（变更实时生效）；未命中降级 JWT claims
                                List<String> roles = session.snapshot() != null
                                        ? session.snapshot().roles() : claims.roles();
                                if (roles != null && !roles.isEmpty()) {
                                    headers.set(HEADER_USER_ROLES, String.join(",", roles));
                                }
                                // 权限标识符：仅快照有（降级时不注入，下游 @RequirePerm 端点 fail-closed）
                                if (session.snapshot() != null && !session.snapshot().perms().isEmpty()) {
                                    headers.set(HEADER_USER_PERMS, String.join(",", session.snapshot().perms()));
                                }
                                // 部门ID：仅快照有（P3.5b 部门实体）
                                if (session.snapshot() != null && session.snapshot().deptId() != null) {
                                    headers.set(HEADER_DEPT_ID, String.valueOf(session.snapshot().deptId()));
                                }
                                // 转发 jti，供登出接口写黑名单
                                if (StringUtils.hasText(claims.jti())) {
                                    headers.set(HEADER_JWT_ID, claims.jti());
                                }
                            })
                            .build();
                    // 将修改后的request交给过滤器链继续向后转发
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }

    /**
     * 会话解析结果：是否已吊销 + 用户权限快照（null 代表降级，用 JWT claims 角色）。
     */
    private record SessionState(boolean revoked, AuthSnapshot snapshot) {
    }

    /**
     * 一次 Redis 往返完成三件事（P3.5 起 2 key 扩为 3 key）：
     * <ol>
     * <li>jti 黑名单：单 token 登出作废</li>
     * <li>用户会话版本号：修改密码/强制下线全局作废
     *     （规则：JWT 签发时间 iat ≤ Redis 版本时间戳 → 已吊销）</li>
     * <li>用户权限快照（P3.5）：角色/权限/部门的权威来源，实时生效依据</li>
     * </ol>
     * 容错策略：Redis 访问异常不阻断业务请求（吊销判定放行 + 快照为 null 降级 JWT 角色、
     * 权限置空 → 管理端 @RequirePerm 端点 403 fail-closed，业务端点不受影响），打印警告日志。
     * @param claims JWT解析后的载荷，需要携带jti、userId、iatSeconds(JWT签发时间戳，秒)
     * @return SessionState；revoked=true 时拒绝访问
     */
    private Mono<SessionState> resolveSession(AuthClaims claims) {
        // 单token黑名单key：登出时写入，TTL等于token剩余有效期
        String jtiKey = AuthSessionConstants.BLACKLIST_PREFIX + claims.jti();
        // 用户全局会话版本key：修改密码/全部设备下线时写入时间戳
        String verKey = AuthSessionConstants.BLACKVER_PREFIX + claims.userId();
        // 用户权限快照key（P3.5）：登录/权限变更时 tenant-service 写入
        String snapKey = AuthSessionConstants.SNAPSHOT_PREFIX + claims.userId();

        // multiGet 一次redis批量查询三个key，减少网络RT，避免多次独立Redis请求
        return redisTemplate.opsForValue().multiGet(Arrays.asList(jtiKey, verKey, snapKey))
                .map(values -> {
                    String jtiVal = values == null ? null : values.get(0);
                    String verVal = values == null ? null : values.get(1);
                    String snapVal = values == null ? null : values.get(2);

                    // 条件1：该jti存在黑名单 → 当前这个token已经登出，直接吊销
                    if (StringUtils.hasText(jtiVal)) {
                        return new SessionState(true, null);
                    }

                    // 条件2：用户存在全局会话版本号
                    if (StringUtils.hasText(verVal)) {
                        try {
                            // JWT签发时间(iat) <= 版本时间戳：代表本token是版本更新之前签发的，全部作废
                            if (claims.iatSeconds() <= Long.parseLong(verVal)) {
                                return new SessionState(true, null);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("会话版本号非法，忽略: key={}, value={}", verKey, verVal);
                        }
                    }
                    // 未吊销：解析权限快照（缺失/损坏返回 null，走降级）
                    return new SessionState(false, AuthSnapshot.parse(snapVal));
                })
                .onErrorResume(e -> {
                    // redis访问异常，不阻断请求：吊销判定放行 + 快照降级，仅打warn日志
                    log.warn("Redis 会话校验失败，放行请求并降级快照: {}", e.getMessage());
                    return Mono.just(new SessionState(false, null));
                });
    }

    /**
     * 判断接口是否属于鉴权白名单
     * 白名单接口：登录接口、健康检查、swagger文档接口，不需要携带token
     * ⚠️安全收口（P3.5d）：actuator 仅放行 /actuator/health（存活探测必需），
     * 其余端点（env/beans/gateway 等）一律要求鉴权——此前 /actuator/** 全放行叠加
     * gateway 端点暴露，未认证调用方可运行期改写网关路由，形成鉴权绕过面。
     * @param request 请求对象
     * @param path 请求路径
     * @return true=白名单放行；false=需要校验JWT
     */
    private boolean isWhitelisted(ServerHttpRequest request, String path) {
        // 登录接口限定POST请求
        if ("/api/v1/auth/login".equals(path) && HttpMethod.POST == request.getMethod()) {
            return true;
        }
        // 健康检查（仅 health 单端点）、swagger相关文档接口
        return pathMatcher.match("/actuator/health", path)
                || pathMatcher.match("/swagger-ui/**", path)
                || pathMatcher.match("/swagger-ui.html", path)
                || pathMatcher.match("/v3/api-docs/**", path)
                || pathMatcher.match("/webjars/**", path);
    }

    /**
     * 从 Authorization 请求头提取 Bearer 后面的token字符串
     * @param request 请求
     * @return token字符串，没有则返回null
     */
    private String resolveBearerToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        return null;
    }

    /**
     * 移除客户端提交的可伪造身份请求头
     * @param request 原始请求
     * @return 处理后request
     */
    private ServerHttpRequest stripSpoofableHeaders(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            for (String h : SPOOFABLE_HEADERS) {
                headers.remove(h);
            }
        }).build();
    }

    /**
     * 返回401未授权JSON响应
     * Gateway是webflux环境，不能直接复用普通spring‑web的R<T>工具类，手动构造JSON响应体
     * @param exchange 请求上下文
     * @param message 错误提示信息
     * @return Mono<Void> 响应
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 构造统一错误JSON格式：{"code":401,"message":"xxx"}
        String body = "{\"code\":401,\"message\":\"" + escape(message) + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * JSON简单转义：处理消息中的双引号、反斜杠，防止JSON格式破坏
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 过滤器执行顺序
     * 负数，保证在网关内置路由转发过滤器**之前执行**，鉴权发生在转发业务服务之前
     * @return 执行order，数值越小优先级越高
     */
    @Override
    public int getOrder() {
        // 负数，先于路由转发前的内置过滤器执行
        return -100;
    }
}
