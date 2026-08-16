# agent-gateway API 网关

> 端口 9080。统一入口：路由转发 + JWT 鉴权 + 转发头注入 + CORS。
> 实现见 `agent-gateway` 的 `AuthGlobalFilter`；登录/隔离完整流程见 [`docs/architecture/tenant-auth.md`](../architecture/tenant-auth.md)。

## 路由表（`spring.cloud.gateway.server.webflux.routes`）

> 注意：Spring Cloud Gateway 4.3.0（2025.0.0）配置命名空间为 `server.webflux.routes`，旧 `gateway.routes` 前缀不生效。

| 路由 ID | 路径谓词 | 目标 |
|---|---|---|
| `tenant-service-auth` | `/api/v1/auth/**` | `lb://tenant-service` |
| `tenant-service-mgmt` | `/api/v1/users/**`, `/api/v1/tenants/**`, `/api/v1/roles/**` | `lb://tenant-service` |
| `agent-core-service` | `/api/v1/tasks/**` | `lb://agent-core-service` |
| `tool-service` | `/api/v1/tools/**` | `lb://tool-service` |
| `file-service` | `/api/v1/files/**` | `lb://file-service` |
| `agent-core-reimbursements` | `/api/v1/reimbursements/**`（P2a-重构单据闭环） | `lb://agent-core-service` |
| `agent-core-audit-data` | `/api/v1/audit/**`（P2b 工具-facing：OCR 回写/预算/规则/重复检测） | `lb://agent-core-service` |
| `agent-core-rules` | `/api/v1/rules/**`（P2c 规则可视化配置） | `lb://agent-core-service` |

Discovery locator 已关闭，仅走上述显式路由。

## 鉴权白名单

以下请求不校验 JWT，直接放行（同时剥除客户端伪造的身份头）：

| 路径 | 说明 |
|---|---|
| `POST /api/v1/auth/login` | 登录（需凭据） |
| `/actuator/**` | 健康检查 |
| `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`, `/webjars/**` | 各服务接口文档 |

其余请求必须携带 `Authorization: Bearer <token>`，否则返回 401。

## 转发头注入

JWT 校验通过后，网关用 **JWT 载荷覆盖**同名请求头再转发给下游服务（客户端伪造值无效）：

| 头 | 来源 | 说明 |
|---|---|---|
| `X-Tenant-Id` | `claims.tenantId` | 下游多租户拦截器/`TenantIdFilter` 读取 |
| `X-User-Id` | `claims.userId` | 下游业务（如 `/auth/me`）读取 |
| `X-Username` | `claims.username` | 登录名（有值才注入） |
| `X-User-Roles` | `claims.roles` | 角色编码，逗号拼接（非空才注入） |
| `X-Jwt-Jti` | `claims.jti` | token 唯一 ID，下游 `/auth/logout` 据此写黑名单 |

## 会话作废校验

每次请求解析 JWT 后，网关再做一次 Redis 校验（`ReactiveStringRedisTemplate` 单次 `MGET`）：

- `finaudit:auth:blacklist:{jti}` 命中 → 该 token 已登出 → 401
- `finaudit:auth:blackver:{userId}` 命中且 `iat ≤ 版本号` → 用户被踢下线 → 401

Redis 不可用时 fail-open（WARN + 放行）。Key 约定与写入侧见 [`docs/architecture/tenant-auth.md`](../architecture/tenant-auth.md) 第 6 节。

## 401 响应格式

```json
{ "code": 401, "message": "缺少 Authorization: Bearer <token>" }
```

| 场景 | message |
|---|---|
| 无 `Authorization` 头 | `缺少 Authorization: Bearer <token>` |
| token 无效 / 过期 / 签名错误 | `token 无效或已过期` |
| 载荷缺 userId/tenantId | `token 载荷缺少用户或租户信息` |
| Redis 黑名单命中（已登出 / 被踢下线） | `登录已失效，请重新登录` |

## 跨域（P1.5 前端）

`globalcors` 已开启：`allowedOrigins=*`、`allowedMethods=*`、`allowCredentials=false`。生产按域名收敛。

## 约定

- JWT 密钥：`finaudit.jwt.secret` ← `${FINAUDIT_JWT_SECRET}`（HS256 ≥32 字符，缺失则启动失败）。与 tenant-service 共用，保证验签一致。
- 网关基于 WebFlux，**不引入 common-code**，401 直接写 JSON。
