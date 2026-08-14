# 租户鉴权与多租户隔离

> P1.4 落地：JWT 认证 + 网关转发头注入 + `TenantLineInnerInterceptor` 全链路数据隔离。
> 相关接口见 [`docs/api/gateway.md`](../api/gateway.md)、[`docs/api/tenant-service.md`](../api/tenant-service.md)。

## 1. 总体思路

```
浏览器                    agent-gateway(9080)              tenant-service(9203)
  │  POST /auth/login        │                                  │
  │─────────────────────────>│  白名单放行（剥伪造头）            │
  │                          │─────────────────────────────────>│  BCrypt 校验
  │ <──────── token ─────────│<── JWT ──────────────────────────│  签发 JWT
  │                          │                                  │
  │  GET /tasks  + Bearer    │                                  │
  │─────────────────────────>│  parseToken 验签                 │
  │                          │  注入 X-Tenant-Id/X-User-Id/…    │
  │                          │─────────────────────────────────>│  TenantIdFilter
  │                          │                                  │  设上下文 → 拦截器过滤
  │ <───── 本租户数据 ───────│<─────────────────────────────────│
```

- **认证**：网关统一校验 JWT（`AuthGlobalFilter`），下游服务不再各自鉴权。
- **授权**（按角色 admin-only 等）：本期只做「必须登录」，JWT 已携带 roles 并转发 `X-User-Roles`，P2/P3 在此上加按角色授权。
- **数据隔离**：`TenantLineInnerInterceptor`（MyBatis-Plus）对 SQL 自动追加 `tenant_id = ?`，INSERT 自动补 `tenant_id`。

## 2. JWT（common-jwt-starter）

纯 Java starter，无 Web 依赖，网关(WebFlux)与 tenant-service(WebMVC)共用：

- 签发：tenant-service `AuthService.login` → `JwtTokenProvider.createToken(userId, tenantId, username, roles)`（每次签发自动生成唯一 `jti`）
- 解析：网关 `AuthGlobalFilter` → `JwtTokenProvider.parseToken(token)`（验签 + 过期 + 结构，返回含 `jti`/`iatSeconds` 的 `AuthClaims`）
- 载荷（HS256，`secret` = `${FINAUDIT_JWT_SECRET}` ≥32 字符）：

| claim | 含义 |
|---|---|
| `sub` | username |
| `userId` / `tenantId` | 登录主体 |
| `roles` | 角色编码列表 |
| `jti` | 唯一 token ID（会话作废依据） |
| `iat` / `exp` | 签发 / 过期（`expireHours` 默认 24h） |

## 3. 租户上下文传播（两条链路）

`TenantContextHolder`（ThreadLocal，common-code）承载当前租户：

- **HTTP 链路**：网关注入 `X-Tenant-Id` → 下游 `TenantIdFilter`（Servlet）读取并 `setTenantId`（finally 清理）。
- **MQ 链路**：消费线程无 HTTP 头，消费者入口用消息体 `tenantId` 显式设置上下文，如
  `TaskSubmitConsumer` / `ToolResultConsumer`（agent-core）、`ToolExecuteConsumer`（tool-service）：
  `TenantContextHolder.runWith(msg.tenantId(), () -> orchestrator.start(...))`（try/finally 清理）。

**兜底**：拦截器读不到上下文时回退 `tenant_id=1` 并 WARN（P1 单租户务实策略；P2 多租户改为 fail-fast）。

## 4. 多租户隔离

`CommonMybatisPlusAutoConfiguration` 装配 `TenantLineInnerInterceptor` + `TenantLineHandler`：

- `getTenantId()`：读 `TenantContextHolder`，缺省回退 1 + WARN
- `getTenantIdColumn()`：`tenant_id`
- `ignoreTable()`：`sys_tenant`（无 tenant_id 列，全局表）

对现有能力的影响：

| 场景 | 行为 |
|---|---|
| 业务表 SELECT/UPDATE/DELETE | 自动拼 `tenant_id = ?` |
| 业务表 INSERT | 自动补 `tenant_id` 列 |
| `sys_tenant` 表 | 不拦截（租户元数据全局可见） |
| `AgentTaskService.pageTask` 分页 | 自动按租户过滤（P1.3 的跨租户泄露由拦截器修复） |
| XML 批量 INSERT（`AgentTaskStepMapper.xml` 等） | 列已含 tenant_id，兼容（P1.4f 验证点） |

## 5. 登录流程时序

1. `POST /api/v1/auth/login {username,password,tenantCode?}`（网关白名单放行，剥伪造头）
2. tenant-service：`tenantCode`（默认 `default`）→ `SysTenantService.getByCode` 校验租户存在且启用
3. 登录请求无 `X-Tenant-Id` 头 → `TenantContextHolder.runWithResult(tenantId, ...)` 查询该租户下的用户
4. 校验用户存在、启用、BCrypt 密码匹配
5. 取角色编码列表 → 组装 `AuthClaims` → 签发 JWT
6. 返回 `LoginVO{token, tokenType:"Bearer", expiresIn, user:{..., roles}}`

## 6. 会话与作废（方案B：JWT + Redis 黑名单）

JWT 无状态，签发的 token 在到期前无法自行失效；方案 B 用 Redis 记录「已作废」的 token，实现登出 / 踢下线，代价是每次请求多一次 Redis 读。

**Redis key（统一前缀 `finaudit:auth:`，常量见 common-jwt-starter `AuthSessionConstants`）：**

| key | 写入方 | 含义 | TTL |
|---|---|---|---|
| `finaudit:auth:blacklist:{jti}` | tenant-service `AuthService.logout` | 单个 token 登出作废 | `expireHours*3600`（安全上界，覆盖剩余有效期） |
| `finaudit:auth:blackver:{userId}` | tenant-service 用户禁用/删除 | 用户级作废版本（时间戳），历史 token 全部失效 | 不设 TTL（每用户一条，极轻） |

**网关校验（`AuthGlobalFilter.isRevoked`）**：一次 `MGET` 读两个 key——

- `blacklist:{jti}` 有值 → 该 token 已登出 → 401「登录已失效，请重新登录」
- `blackver:{userId}` 有值且 `iat ≤ 版本号` → 用户被踢下线 → 401

**fail-open**：Redis 异常时 WARN 并放行（与租户上下文回退的务实策略一致；生产建议改 fail-closed）。

**序列化约束（关键）**：网关用 `ReactiveStringRedisTemplate`（纯 String）。tenant-service 写入侧必须用 **`StringRedisTemplate`**，不能用 common-redis-starter 的 JSON 序列化 `RedisTemplate`——否则 "1"/时间戳会被写成带引号的 JSON 字符串，网关 `Long.parseLong` 解析版本号抛异常、黑名单判断失效。

**登出时序**：
1. 网关解析 token 后注入 `X-Jwt-Jti` 头（与其它身份头同属可伪造头，先剥后写）
2. 客户端 `POST /api/v1/auth/logout`（非白名单，需带 token）
3. tenant-service 读 `X-Jwt-Jti` → 写 `blacklist:{jti}` → 返回成功
4. 此后该 token 访问任意接口 → 网关校验黑名单命中 → 401

**踢下线**：管理员禁用用户（`status=0`）或删除用户 → tenant-service 升级 `blackver:{userId}` 为当前时间戳 → 该用户所有已签发 token 立即失效。

## 7. 安全要点

- 网关注入身份头前**先剥除客户端伪造的同名头**（`X-Tenant-Id`/`X-User-Id`/`X-Username`/`X-User-Roles`/`X-Jwt-Jti`）。
- 用户/角色 CRUD 的租户归属取上下文，**不信任请求体**中的租户字段。
- 密码 BCrypt（`spring-security-crypto`），永不存明文；JWT 密钥走环境变量，不入库。
- `/auth/me`、`/auth/logout` 依赖网关注入的 `X-User-Id`/`X-Jwt-Jti`，直连服务返回 400（防止绕过网关伪造身份）。
- 方案 B 采用**每次请求 1 次 Redis 读**（MGET）校验会话有效性，见「6. 会话与作废」。

## 8. 多租户拦截器的三个坑（P1.4f 端到端验证踩过）

1. **拦截器顺序：`TenantLine` 必须排在 `Pagination` 之前。**
   分页的 COUNT 派生自同一 BoundSql；若 `PaginationInnerInterceptor` 先改写 SQL，COUNT 会从**未加租户条件**的 SQL 派生，跨租户数据量泄露（租户2 任务列表 `total` 显示租户1 的数量）。现配置为 Tenant→Pagination，见 `CommonMybatisPlusAutoConfiguration`。
2. **多行 INSERT（`VALUES (...),(...)`）jsqlparser 5.1 解析失败。**
   报 `ParseException: Encountered unexpected token: ","`。批量新增的 Mapper 方法需加 `@InterceptorIgnore(tenantLine = "true")`——前提是 XML 列清单**已显式写入 `tenant_id`**（如 `AgentTaskStepMapper.insertBatch`、`SysUserRoleMapper.insertBatch`），跳过拦截器安全。
3. **jsqlparser 5.1 保留字列名会让拦截器解析 SELECT/UPDATE 直接崩。**
   `output` 是保留字（`SELECT output FROM ...` 报 `Was expecting WITH`）。该表所有自动 SQL（含 UPDATE `SET output`）都会失败。修复：列名用**反引号**引用——实体 `@TableField(value = "`output`", typeHandler=...)` + XML 列名 `` `output` ``。MySQL 原生接受反引号，jsqlparser 按普通标识符处理，无需改表结构。此后任何手写 SQL 引用该列都须带反引号。
