# 接口文档

> 统一返回结构 `R<T>` 与各服务接口说明。接口均为 JSON；**统一经网关 9080 访问**（`/internal/**` 为服务间契约，网关不路由）。
> ⚠️ 前端与消费方必读：**业务错误一律 HTTP 200，错误码在 body 的 `code`**，见下节「错误语义」。

## 统一返回结构 R&lt;T&gt;

```json
{ "code": 0, "message": "ok", "data": {}, "timestamp": "2026-08-13T18:02:33" }
```

| 字段 | 说明 |
|---|---|
| `code` | 0 成功；非 0 失败（400 参数/业务校验、500 系统异常） |
| `message` | 提示信息 |
| `data` | 业务数据（可能为 null） |
| `timestamp` | 服务端时间 |

## 错误语义（P3.8 澄清）

**业务错误恒为 `HTTP 200` + body `code != 0`。** 判据只有 body 的 `code`，不要按 HTTP 状态分支——
按 HTTP 状态分支的客户端会把所有业务失败都走成功分支（P3.8 走查发现的真实缺陷类型）。

依据：`common-code` 的 `GlobalExceptionHandler` 是 `@RestControllerAdvice`，各 `@ExceptionHandler`
**方法不返回 `ResponseEntity`、也不带 `@ResponseStatus`**，因此异常被包装成 `R<T>` 后仍以 200 输出
（`GlobalExceptionHandler.java:22-52`）：

| 异常 | HTTP | body |
|---|---|---|
| `BizException`（业务校验/越权/租户缺失等） | 200 | `code` = 异常携带的码（**默认 400**）、`message` = 业务文案 |
| `MethodArgumentNotValidException` / `BindException` / `ConstraintViolationException`（JSR303） | 200 | `code=400`、`message` = 首个字段错误 |
| `DuplicateKeyException`（唯一约束冲突） | 200 | `code=400`、`message="数据唯一约束冲突，请检查后重试"` |
| 其他未捕获异常 | 200 | `code=500`、`message="系统繁忙，请稍后再试"` |

`BizException(String)` 的默认码就是 400（`BizException.java:18-20`），故文档里写的
「返回 400」**一律指 body 的 `code=400`，HTTP 状态是 200**。

### HTTP 级错误（少数例外，必须与上面区分）

只有下列情况 HTTP 状态码不为 200，且它们**绕过** `R<T>` 的常规包装路径：

| HTTP | 产生位置 | body | 触发条件 |
|---|---|---|---|
| **401** | 网关 `AuthGlobalFilter.unauthorized`（`AuthGlobalFilter.java:292-300`） | `{"code":401,"message":"…"}`（**无 `data`/`timestamp`**，网关是 WebFlux、手写 JSON） | 缺少/无效/过期 Bearer token；token 载荷缺 `userId`/`tenantId`；jti 黑名单命中或 `blackver` 命中（登录已失效）。白名单仅 `/api/v1/auth/login`(POST)、`/actuator/health`、swagger 相关 |
| **403** | common-code `PermissionInterceptor.writeForbidden`（`PermissionInterceptor.java:97-102`） | `{"code":403,"message":"无权限访问"}` | `@RequirePerm` 端点：无用户上下文，或权限码不在 `X-User-Perms` 快照里（fail-closed） |
| 容器层 | Servlet 容器 / 框架在进入 `@RestController` **之前**产生的错误 | 视情况：**服务已显式处理的仍是 HTTP 200 + `R<T>`**，未处理的不保证 | 已实测（P3.8 R7）：上传超 `spring.servlet.multipart.max-file-size`（file-service 为 20MB）→ file-service 的 `FileExceptionHandler` 捕获 `MaxUploadSizeExceededException`，返回 **HTTP 200 + `code=400`「文件大小超出限制」**。⚠️ 其余容器层错误（请求体解析失败、路由/端点不存在等）仍**不要假设 body 是 `R<T>`** |

> 因此判据应是：**先看 HTTP 状态**（401/403 及容器层错误属鉴权、权限、传输层问题），
> **状态为 200 时再看 body 的 `code`**。两者都判，缺一不可。

## 通用约定

- 租户来源：请求头 `X-Tenant-Id`，由网关从 JWT 快照注入；**对外端点缺失即拒绝**（fail-closed，不再回退默认租户 1）；`/internal/**` 由调用方显式传参，同样缺失即拒绝
- 身份头一律由网关注入（`X-Tenant-Id`/`X-User-Id`/`X-Username`/`X-User-Roles`/`X-User-Perms`/`X-Dept-Id`/`X-Jwt-Jti`），客户端同名头**先剥后写**，下游只信任网关注入值
- 权限：`@RequirePerm("<permCode>")` 声明式校验，opt-in（未标注端点放行）；权限码目录见 `docs/database/finaudit-schema.sql` 的 `sys_permission` 种子
- 错误：由 `common-code` 全局异常处理器统一包装为 `R<T>`（HTTP 200），业务校验抛 `BizException`；401/403 见上节

## 服务接口

| 服务 | 端口 | 文档 |
|---|---|---|
| `agent-gateway` | 9080 | [网关（路由/鉴权/注入头/401）](./gateway.md) |
| `agent-core-service` | 9201 | [任务 API + 报销单 API（提交/详情/分页/续跑/修改重跑/撤回/撤销）+ 审批工单 API（分页/详情/审批动作/留痕）+ 规则配置 API（CRUD/启停/发布 Nacos）+ **主动通知 API**（`/api/v1/notify/**`：站内信 + Webhook 配置/台账，见 [`notify.md`](./notify.md)）+ **内部**审核数据 API（`/internal/audit/**`：OCR 回写/预算/规则/重复检测/票据核验 + 越权校验端点）+ 脱敏说明](./agent-core.md) |
| （跨服务） | — | [**主动通知契约**：站内信字段、Webhook 签名算法（接收方实现指引）、事件目录、投递语义与已知取舍](./notify.md) |
| `tool-service` | 9202 | [工具 API（列表/注册/调试直调；`tool:manage`/`tool:execute`；执行链五道关卡）](./tool-service.md) |
| `tenant-service` | 9203 | [租户/用户/角色/部门/权限/认证 API](./tenant-service.md) |
| `rag-service` | 9204 | [RAG 语义检索 API（P4 填充，当前空骨架；**网关未配路由，经网关不可达**）](./rag-service.md) |
| `file-service` | 9205 | [文件 API（上传/详情/预览/下载 + `/internal/files/**` 内部契约）](./file-service.md) |
