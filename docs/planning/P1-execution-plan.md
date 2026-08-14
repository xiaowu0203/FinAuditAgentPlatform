# P1 核心闭环 · 执行点文档（占位）

> 版本: v0.7 ｜ 状态: **P1 完成（P1.5e 五服务联调验收通过）** ｜ 前置依赖: P0 已完成
> 目标: 单 Agent 可用闭环：任务提交 → Agent 拆解执行 → 工具联动 → 结果落库，网关 + MQ + 任务持久化 + DeepSeek + 租户鉴权隔离

---

## 1. P1 范围（已定，源自需求与已确认决策）

| 模块 | 职责 | 要点 |
|---|---|---|
| `agent-gateway` | 路由 + 鉴权（占位） | P0 骨架已建，P1 补路由规则、统一鉴权过滤 |
| `tenant-service` | 租户 / 用户 / 权限 | 多租户数据隔离（配合 MP 多租户插件）、JWT |
| `agent-core-service` | Agent 调度 / 任务 | **单 Agent** + 自研状态机 + 任务持久化 + 断点续跑接口 + MQ 编排 |
| `tool-service` | 工具注册 / 执行 | 工具注册表 + 执行器，供 Agent 联动 |
| common-* | 能力复用 | web(R<T>/异常) / redis / mybatisplus / model(DeepSeek 实现) / trace |

**已确认的决策（P0 带入，勿改）：**
- Agent 底座 = Spring AI 官方 core + 自研状态机/持久化/MQ 编排；spring-ai-alibaba 仅用于 A2A 传输等边角（P3+）
- P1 多 Agent 通信用 MQ 先行，A2A 确定在 P3+ 实现
- 金额计算必须 Decimal，严禁 float/double
- 模型默认 DeepSeek，密钥走环境变量 `FINAUDIT_MODEL_API_KEY`
- MySQL 5.7，库名 `finaudit`；本地中间件全部复用（Nacos 8848 / RabbitMQ 5672 / MySQL 3306 / Redis 6379）
- Nacos 3.2.2 前后端分离，初始化脚本已就绪（见 `docs/deploy/README.md`）

## 2. 执行点进度

> 按项目约定：分阶段推进，每阶段完成经用户确认后提交推送。

### 已完成

- [x] **P1.1 数据库设计**：`docs/database/tables.md` + `finaudit-schema.sql`（8 表 + seed），本机 MySQL 执行验证通过
- [x] **P1.2 模型接入**：`common-model-starter` DeepSeek 实现（Spring AI core `OpenAiChatModel`），真实调用 + 单测通过
- [x] **P1.3a 模块骨架**：`agent-core-service` / `tool-service` 新建并入父 POM
- [x] **P1.3b MQ 拓扑**：`common-mq-starter` 交换机/三队列/DLQ + 共享消息 DTO（`TaskSubmit/ToolExecute/ToolResult`）
- [x] **P1.3c agent-core 状态机与编排**：`AgentOrchestrator`（PENDING→RUNNING→SUCCESS/FAILED，步骤机 + 失败重试 ≤3 + 断点续跑）、`TaskPlanner`（LLM 拆解 + 内置模板回退）、消费者/服务/控制器
- [x] **P1.3d tool-service 工具执行**：`ToolRegistry`/`ToolExecutionLog` 实体 + `AmountVerifyTool`（BigDecimal）+ 消费者/注册表/控制器
- [x] **P1.3e 端到端联调**：提交任务 → LLM 拆解 → `amount_verify` 工具（MQ 往返）→ 结果落库 → SUCCESS；失败重试 3 次 → FAILED；`resume` 断点续跑通过（详见 `docs/architecture/task-orchestration.md`）
- [x] **P1.4a common-jwt-starter**：`JwtProperties` / `AuthClaims` / `JwtTokenProvider`（jjwt 0.12.6，HS256）+ 自动装配，网关与 tenant-service 共用签发/解析
- [x] **P1.4b 租户上下文 + 多租户拦截器**：`TenantContextHolder` + `TenantIdFilter`（common-code）+ `TenantLineInnerInterceptor`（common-mybatisplus，ignore `sys_tenant`，缺上下文回退租户1+WARN）；三个 MQ 消费者入口包 `runWith(msg.tenantId())`
- [x] **P1.4c tenant-service（完整 CRUD）**：端口 9203，`SysTenant/SysUser/SysRole/SysUserRole` 实体（`from/apply` 转换）+ DTO/VO + Mapper/Service/Controller + `AuthService`（BCrypt 校验 → JWT 签发）；用户/角色/租户全量增删改查 + 用户绑角色
- [x] **P1.4d 网关路由 + 鉴权过滤 + CORS**：`AuthGlobalFilter`（白名单 / Bearer 校验 / 注入 `X-Tenant-Id`/`X-User-Id`/`X-Username`/`X-User-Roles` / 401 JSON），显式路由（`server.webflux.routes`）+ globalcors
- [x] **P1.4e 种子密码 + 配置 + 文档**：`admin123` 真实 BCrypt 哈希回填 `finaudit-schema.sql` + 本机库定向 UPDATE；`.env.example` 加 `FINAUDIT_JWT_SECRET`；新增 `docs/api/tenant-service.md` / `docs/api/gateway.md` / `docs/architecture/tenant-auth.md`
- [x] **P1.4f 端到端验证**：全量构建 + 4 服务启动；登录（直连 + 经网关，roles 正确）；网关鉴权（无 token 401 / 带 token 注入身份头）；`/auth/me`；用户/租户/角色 CRUD；**跨租户隔离**（租户2 `acme` 登录 → 任务列表为空 → 提交任务仅租户2 可见，租户1 列表无该任务）；**MQ 回归**（任务 SUCCESS，2 步，批量 INSERT 步骤 + 拦截器 + 消费线程租户上下文三者打通，步骤 `output` 正确落库返回）；错误路径（错密码/禁用用户 → 400 统一口径）。踩过的三个坑（拦截器顺序、多行 INSERT `@InterceptorIgnore`、`output` 保留字反引号）见 [`docs/architecture/tenant-auth.md`](../architecture/tenant-auth.md) §7
- [x] **P1.4g 方案B 会话作废（JWT + Redis 黑名单）**：`JwtTokenProvider` 每次签发带唯一 `jti`（`AuthClaims` 新增 `jti`/`iatSeconds`）；网关 `AuthGlobalFilter` 每请求 `MGET` 校验 `blacklist:{jti}`（登出）与 `blackver:{userId}`（用户级踢下线），命中 401、Redis 异常 fail-open；tenant-service `POST /auth/logout` 写黑名单（TTL=`expireHours*3600`），用户禁用/删除自动升级 `blackver`；写入侧 `StringRedisTemplate` 与网关读取侧 `ReactiveStringRedisTemplate` 序列化对齐（详见 [`docs/architecture/tenant-auth.md`](../architecture/tenant-auth.md) §6）。验证：登录→登出→旧 token 401→重登可用；禁用用户→其全部已签发 token 立即失效
- [x] **P1.5 最小前端（TS，全部页面）**：Vue3 + Vite 6 + Element Plus + Pinia + axios + TS，登录 / 任务工作台 / 任务列表 / 任务详情四页，axios 封装（Bearer 注入 + `code!=0`/401 双路径跳登录）、路由登录守卫、PENDING/RUNNING 轮询、续跑按钮（仅 PENDING/RUNNING，对齐后端契约）。`vue-tsc` + `vite build` 通过。详见 [`docs/planning/P1.5-frontend-plan.md`](./P1.5-frontend-plan.md)
- [x] **P1.5e P1 完整联调验收（P1 完成定义）**：gateway + tenant + agent-core + tool + 前端全链路，验收清单 6 项全部通过（登录→提交→轮询→SUCCESS→详情见步骤/结果；FAILED + errorMsg + resume 400；登出 401 跳登录；跨租户隔离；localStorage 保持；后端不可达明确提示）
- [x] **P1 收尾加固（v0.7）**：① 动态工具目录注入规划——`TaskPlanner` 经 Feign 直连 tool-service `GET /api/v1/tools` 拉取启用工具注入 LLM 规划 prompt（失败降级内置 `amount_verify`，闭环可用）；② 服务间 Feign 契约统一——客户端接口 + 跨服务 DTO 集中 common-code（`com.finaudit.starter.web.feign`，命名 `工程名+Feign`），新增 common-feign-starter 做请求头/token 透传，tool-service 统一单一控制器入口、租户走 `X-Tenant-Id` 头（服务间复用对外接口，不拆 `/internal`）；③ LLM 审核输出质量——编排注入任务入参 + 前序步骤结果、规划约束（TOOL→LLM、LLM 仅一次）；④ 前端步骤 Markdown 渲染（marked + dompurify）+ 提交样板字段丰富

### 待进行

- [ ] **P2 规划**（P1 已全部完成）

## 3. P1 环境前提

- 本机中间件：Nacos ✅ / RabbitMQ ✅ / MySQL ✅ / Redis ✅（MinIO P2 前启动）
- Nacos dev 命名空间已就绪，占位配置已发布
- 密钥：本地 `.env`（不入库）设置 `FINAUDIT_MODEL_API_KEY`
