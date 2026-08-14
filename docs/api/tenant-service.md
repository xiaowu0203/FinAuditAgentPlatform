# tenant-service 租户/用户/角色 API

> 端口 9203。鉴权流程与多租户隔离见 [`docs/architecture/tenant-auth.md`](../architecture/tenant-auth.md)。
> 除登录外均需经网关访问（网关注入 `X-Tenant-Id`/`X-User-Id`/`X-Jwt-Jti`）；`/auth/me` 需要 `X-User-Id`、`/auth/logout` 需要 `X-Jwt-Jti` 头，直连无法访问。

## POST /api/v1/auth/login — 登录（白名单）

无鉴权（网关放行）。成功签发 JWT，`Authorization: Bearer <token>` 用于后续请求。

```json
{ "username": "admin", "password": "admin123" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | ✅ | 登录名 |
| `password` | string | ✅ | 密码（BCrypt 校验） |
| `tenantCode` | string | ❌ | 租户编码，为空默认 `default` |

响应：

```json
{ "code": 0, "message": "ok", "data": {
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": { "id": 1, "tenantId": 1, "username": "admin", "realName": "系统管理员", "phone": null, "roles": ["admin"] }
}}
```

失败场景：租户不存在 / 租户禁用 / 用户名或密码错误 / 用户禁用 → 400（`BizException` 包装，`code` 非 0）。

## GET /api/v1/auth/me — 当前用户信息

需经网关注入 `X-User-Id`（网关从 JWT 取）。响应 `data` 同登录中的 `user`。

## POST /api/v1/auth/logout — 登出

需经网关注入 `X-Jwt-Jti`（网关从 JWT 取 token ID）。将当前 token 写入 Redis 黑名单（TTL=`expireHours*3600`），此后该 token 访问任意接口被网关 401 拒绝。响应 `data` 为 null。

| 请求头 | 说明 |
|---|---|
| `Authorization: Bearer <token>` | 当前登录 token |
| `X-Jwt-Jti` | 网关注入的 token ID，黑名单 key 依据 |

失败场景：缺少 `X-Jwt-Jti`（绕过网关直连）→ 400。禁用/删除用户会把其全部 token 踢下线（用户级版本号 `blackver`），详见 [`tenant-auth.md`](../architecture/tenant-auth.md) 第 6 节。

## 用户管理 /api/v1/users

> 所属租户一律取请求上下文（网关注入 `X-Tenant-Id`），不信任请求体中的租户字段。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/users` | 新增用户（密码 BCrypt 落库，可同时绑定角色） |
| PUT | `/api/v1/users/{id}` | 更新用户（`password` 非空才重置密码） |
| PUT | `/api/v1/users/{id}/roles` | 绑定角色（替换式，以传入列表为准，空列表即清空） |
| DELETE | `/api/v1/users/{id}` | 逻辑删除 |
| GET | `/api/v1/users/{id}` | 详情（含角色列表） |
| GET | `/api/v1/users` | 分页查询（`pageNum`/`pageSize`/`keyword`） |

POST 请求体：

```json
{ "username": "zhangsan", "password": "123456", "realName": "张三", "phone": "13800138000", "status": 1, "roleIds": [2] }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | ✅ | 登录名，≤64 字符，同租户唯一 |
| `password` | string | ✅ | 6–32 字符，BCrypt 落库 |
| `realName` | string | ❌ | 真实姓名 |
| `phone` | string | ❌ | 手机号 |
| `status` | int | ❌ | 1 启用 / 0 禁用，默认 1 |
| `roleIds` | array | ❌ | 待绑定角色 ID 列表 |

`UserVO`（列表项）：`id, tenantId, username, realName, phone, status, createdAt`。
`UserDetailVO`（详情）：`UserVO + roles: [RoleVO]`。

## 租户管理 /api/v1/tenants

> `sys_tenant` 为全局表（多租户拦截器忽略），CRUD 不受租户上下文过滤。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/tenants` | 新增租户（`tenantCode` 全局唯一） |
| PUT | `/api/v1/tenants/{id}` | 更新租户 |
| DELETE | `/api/v1/tenants/{id}` | 逻辑删除 |
| GET | `/api/v1/tenants/{id}` | 详情 |
| GET | `/api/v1/tenants` | 分页（`pageNum`/`pageSize`/`keyword` 按编码/名称过滤） |

`TenantVO`：`id, tenantCode, tenantName, status, createdAt`。

## 角色管理 /api/v1/roles

> 所属租户取上下文；同租户下 `roleCode` 唯一。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/roles` | 新增角色 |
| PUT | `/api/v1/roles/{id}` | 更新角色 |
| DELETE | `/api/v1/roles/{id}` | 逻辑删除 |
| GET | `/api/v1/roles/{id}` | 详情 |
| GET | `/api/v1/roles` | 当前租户全部角色（角色选择器用） |

`RoleVO`：`id, tenantId, roleCode, roleName, createdAt`。

## 分页响应结构

`data` 为 MyBatis-Plus `Page`：

```json
{ "code": 0, "message": "ok", "data": { "total": 3, "size": 10, "current": 1, "records": [ /* VO */ ] } }
```

## 种子账号

- `admin` / `admin123`（租户 `default`，角色 `admin`），初始密码在 `docs/database/finaudit-schema.sql` 中以真实 BCrypt 哈希落库。
