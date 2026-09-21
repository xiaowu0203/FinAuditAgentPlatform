# tenant-service 租户/用户/角色/部门/权限/认证 API

> 端口 9203。鉴权流程与多租户隔离见 [`docs/architecture/tenant-auth.md`](../architecture/tenant-auth.md)。
> 除登录外均需经网关访问（网关注入 `X-Tenant-Id`/`X-User-Id`/`X-User-Perms`/`X-Jwt-Jti`）；`/auth/me` 需要 `X-User-Id`、`/auth/logout` 需要 `X-Jwt-Jti` 头，直连无法访问。
> 除登录外所有端点经 `@RequirePerm` 权限码校验（opt-in）：无权限由 `PermissionInterceptor` 返回 **HTTP 403** + `{"code":403,"message":"无权限访问"}`；业务失败为 **HTTP 200 + body `code=400`**（见 [`README.md`](./README.md) 第「错误语义」）。

## POST /api/v1/auth/login — 登录（白名单，无权限码）

无鉴权（网关放行）。成功签发 JWT，`Authorization: Bearer <token>` 用于后续请求。

```json
{ "username": "admin", "password": "admin123" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | ✅ | 登录名 |
| `password` | string | ✅ | 密码（BCrypt 校验） |
| `tenantCode` | string | ❌ | 租户编码，为空默认 `default` |

响应（`LoginVO`）：

```json
{ "code": 0, "message": "ok", "data": {
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": { "id": 1, "tenantId": 1, "username": "admin", "realName": "系统管理员", "phone": null,
            "roles": ["admin"],
            "perms": ["user:list", "user:create", "user:update", "user:delete", "user:assign-role",
                      "role:list", "role:create", "role:update", "role:delete", "role:assign-perm",
                      "dept:manage", "dept:create", "dept:update", "dept:delete", "tenant:manage",
                      "tool:manage", "tool:execute", "rule:manage", "reimb:viewAll", "task:viewAll",
                      "audit:viewAll", "audit:approve", "budget:viewAll", "dashboard:admin"] }
}, "timestamp": "2026-08-13T18:02:33" }
```

`user` 为 `UserInfoVO`：`id, tenantId, username, realName, phone, roles[], perms[]`。
上例的 `perms` 即种子角色 `admin` 的**完整权限码集合**（`finaudit-schema.sql:559-567` 把全部权限码授予角色 1）。
**`perms`（权限标识符列表）是 P3.5 起前端菜单/按钮/路由动态渲染的依据**，与网关注入的
`X-User-Perms` 同源（`AuthService.login` 用 `permissionService.listPermCodesByUser` 组装，
并写 Redis 权限快照；`UserInfoVO.java:22-35`、`AuthService.java:126-136`）。

`/auth/me` 返回同一 `UserInfoVO`（含 `perms`）。

**校验顺序（P3.5d）**：租户编码 → 租户存在 → 租户启用 → 防爆破锁定（连续 5 次失败锁 15 分钟）
→ **BCrypt 密码校验** → （密码通过后）**用户禁用判断** → 角色/权限组装 → 签 JWT + 写快照。
密码先行、禁用后置是为防账号存在性泄露：此前先判禁用，攻击者凭「用户已被禁用」文案即可**无密码探测账号存在**；
未知用户也对固定 BCrypt 哈希做同价比对以抹平时序差（`AuthService.java:103-137`）。

失败场景（均 HTTP 200，`code=400`）：`租户不存在: {code}` / `租户已禁用: {code}` /
`用户名或密码错误`（未知用户与密码错**同文案**）/ `账号已被禁用，请联系管理员`（仅密码已验证通过后）。

## GET /api/v1/auth/me — 当前用户信息

需经网关注入 `X-User-Id`（网关从 JWT 取，缺失 → `code=400`「缺少用户标识 X-User-Id，请通过网关访问」）。
响应 `data` 为 `UserInfoVO`（同登录中的 `user`，含 `perms`）。无权限码（登录即可）。

## POST /api/v1/auth/logout — 登出

需经网关注入 `X-Jwt-Jti`（网关从 JWT 取 token ID）。将当前 token 写入 Redis 黑名单（TTL=`expireHours*3600`），此后该 token 访问任意接口被网关 **HTTP 401** 拒绝。响应 `data` 为 null。无权限码（登录即可）。

| 请求头 | 说明 |
|---|---|
| `Authorization: Bearer <token>` | 当前登录 token |
| `X-Jwt-Jti` | 网关注入的 token ID，黑名单 key 依据 |

失败场景：缺少 `X-Jwt-Jti`（绕过网关直连）→ 200 + `code=400`「缺少 token 标识 X-Jwt-Jti，请通过网关访问」。禁用/删除用户会把其全部 token 踢下线（用户级版本号 `blackver`），详见 [`tenant-auth.md`](../architecture/tenant-auth.md) 第 6 节。

## 用户管理 /api/v1/users

> 所属租户一律取请求上下文（网关注入 `X-Tenant-Id`），不信任请求体中的租户字段。

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| POST | `/api/v1/users` | `user:create` | 新增用户（密码 BCrypt 落库，可同时绑定角色与部门） |
| PUT | `/api/v1/users/{id}` | `user:update` | 更新用户（`password` 非空才重置密码） |
| PUT | `/api/v1/users/{id}/roles` | `user:assign-role` | 绑定角色（替换式，以传入列表为准，空列表即清空；在线用户权限即时生效） |
| DELETE | `/api/v1/users/{id}` | `user:delete` | 逻辑删除（同时踢下全部会话） |
| GET | `/api/v1/users/{id}` | `user:list` | 详情（含角色列表） |
| GET | `/api/v1/users` | `user:list` | 分页查询（`pageNum`/`pageSize`/`keyword`） |

POST 请求体（`UserCreateRequest`）：

```json
{ "username": "zhangsan", "password": "123456", "realName": "张三", "phone": "13800138000",
  "deptId": 3, "status": 1, "roleIds": [2] }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | ✅ | 登录名，≤64 字符，同租户唯一 |
| `password` | string | ✅ | 6–32 字符，BCrypt 落库 |
| `realName` | string | ❌ | 真实姓名 |
| `phone` | string | ❌ | 手机号 |
| `deptId` | long | ❌ | 部门 ID（员工级归属，P3.5b，可为空） |
| `status` | int | ❌ | 1 启用 / 0 禁用，默认 1 |
| `roleIds` | array\<long\> | ❌ | 待绑定角色 ID 列表 |

`UserVO`（列表项）：`id, tenantId, username, realName, phone, deptId, deptName, status, createdAt`。
`UserDetailVO`（详情）：`UserVO + roles: [RoleVO]`。
**`deptId`/`deptName` 均为 P3.5b 新增字段**（`UserVO.java:23-32`、`UserDetailVO.java:25-35`）；未绑定部门时为 null。`phone` 对外按 `@Mask(PHONE)` 脱敏。

## 租户管理 /api/v1/tenants

> `sys_tenant` 为全局表（多租户拦截器忽略），CRUD 不受租户上下文过滤。
> **权限码为类级 `@RequirePerm("tenant:manage")`**，对本控制器全部端点生效（`SysTenantController.java:26`）。

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| POST | `/api/v1/tenants` | `tenant:manage` | 新增租户（`tenantCode` 全局唯一） |
| PUT | `/api/v1/tenants/{id}` | `tenant:manage` | 更新租户 |
| DELETE | `/api/v1/tenants/{id}` | `tenant:manage` | 逻辑删除 |
| GET | `/api/v1/tenants/{id}` | `tenant:manage` | 详情 |
| GET | `/api/v1/tenants` | `tenant:manage` | 分页（`pageNum`/`pageSize`/`keyword` 按编码/名称过滤） |

`TenantVO`：`id, tenantCode, tenantName, status, createdAt`。

## 角色管理 /api/v1/roles

> 所属租户取上下文；同租户下 `roleCode` 唯一。

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| POST | `/api/v1/roles` | `role:create` | 新增角色 |
| PUT | `/api/v1/roles/{id}` | `role:update` | 更新角色 |
| DELETE | `/api/v1/roles/{id}` | `role:delete` | 逻辑删除（同步清理用户-角色/角色-权限映射并刷新在线用户权限） |
| GET | `/api/v1/roles/{id}` | `role:list` | 详情 |
| GET | `/api/v1/roles` | `role:list` | 当前租户全部角色（角色选择器用） |
| GET | `/api/v1/roles/{id}/permissions` | `role:assign-perm` | **角色已分配权限**：`data` 为权限 ID 数组 `List<Long>`，供分配界面回显勾选 |
| PUT | `/api/v1/roles/{id}/permissions` | `role:assign-perm` | **分配角色权限**：替换式，请求体 `{ "permIds": [1,2,3] }`，空列表即清空；在线用户即时生效（刷新权限快照） |

`RoleVO`：`id, tenantId, roleCode, roleName, createdAt`。

`GET|PUT /roles/{id}/permissions` 由 `SysRoleController` 提供（`SysRoleController.java:75-90`），
`data` 取自 `SysPermissionService.listPermIdsByRole` / `replaceRolePermissions`；
两个端点均先 `roleService.getRequired(id)` 校验角色存在与租户归属。

PUT 请求体（`RolePermAssignRequest`）：

```json
{ "permIds": [1, 2, 20, 24] }
```

## 权限目录 /api/v1/permissions（P3.5a）

> 权限标识符目录（平台级全局表，所有租户共用同一套码），供角色分配界面渲染勾选树。
> **权限码为类级 `@RequirePerm("role:assign-perm")`**（`SysPermissionController.java:18`）。

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/v1/permissions` | `role:assign-perm` | 权限目录：启用权限按分组 + ID 排序，`data` 为 `PermissionVO` 列表 |

`PermissionVO` 取自 `sys_permission` 实体（字段见 [`docs/database/tables.md`](../database/tables.md)），
含 `id, permCode, permName, permType, groupName` 等。种子明细见
`docs/database/finaudit-schema.sql:533-557`（`user:*` / `role:*` / `dept:*` / `tenant:manage` /
`tool:manage` / `tool:execute` / 财务业务 `rule:manage` / `reimb:viewAll` / `task:viewAll` /
`audit:viewAll` / `audit:approve` / `budget:viewAll` / `dashboard:admin`）。

## 部门管理 /api/v1/depts（P3.5b）

> 部门树为公用数据（报销单创建页选择器、用户管理页）：**GET 登录即可、不挂权限码**；写操作挂操作级码。所属租户取上下文。

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/v1/depts` | 登录即可 | 部门树（全部含停用；报销选择器公用） |
| GET | `/api/v1/depts/exists?deptId=` | 登录即可 | 部门是否存在且启用（内部读，agent-core 校验用） |
| POST | `/api/v1/depts` | `dept:create` | 新增部门（parent 非根须存在；租户内部门名唯一） |
| PUT | `/api/v1/depts/{id}` | `dept:update` | 编辑（改名/换父/停用；parent 变更防环） |
| DELETE | `/api/v1/depts/{id}` | `dept:delete` | 删除（有子部门/用户引用拒删） |

`DeptVO`：`id, parentId, deptName, status, children[]`（树递归）。

## 权限码清单（本服务）

| 权限码 | 覆盖端点 | 声明位置 |
|---|---|---|
| `user:list` | `GET /users`、`GET /users/{id}` | 方法级（`SysUserController.java:69,76`） |
| `user:create` | `POST /users` | 方法级（`:39`） |
| `user:update` | `PUT /users/{id}` | 方法级（`:46`） |
| `user:assign-role` | `PUT /users/{id}/roles` | 方法级（`:53`） |
| `user:delete` | `DELETE /users/{id}` | 方法级（`:61`） |
| `role:list` | `GET /roles`、`GET /roles/{id}` | 方法级（`SysRoleController.java:63,70`） |
| `role:create` / `role:update` / `role:delete` | 角色写操作 | 方法级（`:41,48,55`） |
| `role:assign-perm` | `GET|PUT /roles/{id}/permissions`、`/api/v1/permissions` | 方法级 + 类级（`SysRoleController.java:77,85`、`SysPermissionController.java:18`） |
| `tenant:manage` | `/api/v1/tenants/**` 全部 | **类级**（`SysTenantController.java:26`） |
| `dept:create` / `dept:update` / `dept:delete` | 部门写操作 | 方法级（`SysDeptController.java:50,59,66`） |
| （无） | `/auth/login`（白名单）、`/auth/me`、`/auth/logout`、`GET /depts`、`GET /depts/exists` | opt-in 未标注即放行（登录仍由网关强制） |

## 分页响应结构

`data` 为 MyBatis-Plus `Page`：

```json
{ "code": 0, "message": "ok", "data": { "total": 3, "size": 10, "current": 1, "records": [ /* VO */ ] },
  "timestamp": "2026-08-13T18:02:33" }
```

## 种子账号

- `admin` / `admin123`（租户 `default`，角色 `admin`），初始密码在 `docs/database/finaudit-schema.sql` 中以真实 BCrypt 哈希落库。
