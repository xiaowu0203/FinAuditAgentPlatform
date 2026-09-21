# common-jwt-starter

JWT 签发 / 解析通用能力（HS256，纯 Java，无 web 依赖——WebFlux 网关与 Servlet 业务服务共用）。

## 能力

- `JwtTokenProvider#createToken(userId, tenantId, username, roles)`：签发 AccessToken
- `JwtTokenProvider#parseToken(token)`：校验签名/过期并解析出 `AuthClaims`
  （`userId / tenantId / username / roles / jti / issuedAtSeconds`）
- `JwtProperties`（前缀 `finaudit.jwt`）：`secret` / `expireHours`（默认 24）/ `issuer`（默认 `finaudit`）
- 启动自检：**secret 为空或不足 32 字节直接启动失败**，并给出明确提示
  （替代 JJWT `Keys.hmacShaKeyFor` 构造期抛出的晦涩 `WeakKeyException`）

## 三个载荷字段的业务含义

| 字段 | 用途 |
|---|---|
| `userId` | 网关透传 `X-User-Id`，下游 `UserContextHolder` 据此建立登录上下文 |
| `tenantId` | 多租户隔离的唯一来源：网关透传 `X-Tenant-Id`，`TenantLineInnerInterceptor` 据此过滤 |
| `jti` | 令牌唯一编号，配合 Redis 黑名单实现单设备登出 |
| `issuedAtSeconds` | 配合 Redis 会话版本号实现「一键踢全部终端」 |

## 使用

```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-jwt-starter</artifactId>
</dependency>
```

```yaml
finaudit:
  jwt:
    secret: ${FINAUDIT_JWT_SECRET}   # HS256，≥32 字节；缺失即启动失败
    expire-hours: 24
```

## 谁在用

| 服务 | 用途 |
|---|---|
| agent-gateway | 解析 token → 注入身份头（`X-User-Id`/`X-Tenant-Id`/`X-User-Roles`/`X-User-Perms`/`X-Dept-Id`），并做 Redis 吊销校验 |
| tenant-service | 登录时签发 token（`AuthSessionService` 维护快照与吊销） |

## 注意

- **不要在业务服务里用「请求头里的租户」当权威租户**：`X-Tenant-Id` 是普通请求头，
  绕过网关直连服务时可伪造。需要权威租户时要么信任网关注入的上下文（`UserContextHolder`），
  要么像 `ToolAccessGuard`（P3.8 R6-2）那样改用**独立事实来源**（库内归属反查）。
- 密钥只走环境变量 / 配置中心，禁止入库入仓（见 `AGENTS.md` §6）。
