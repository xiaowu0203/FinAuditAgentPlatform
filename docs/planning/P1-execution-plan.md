# P1 核心闭环 · 执行点文档（占位）

> 版本: v0.2 ｜ 状态: **P1.3 核心闭环完成**（P1.4 租户鉴权 / P1.5 前端待进行）｜ 前置依赖: P0 已完成
> 目标: 单 Agent 可用闭环：任务提交 → Agent 拆解执行 → 工具联动 → 结果落库，网关 + MQ + 任务持久化 + DeepSeek

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

### 待进行

- [ ] `tenant-service`：租户/用户/权限 + JWT（P1.4）
- [ ] 网关路由规则 + 鉴权过滤（P1.4，JWT → `X-Tenant-Id`/`X-User-Id` 转发头）
- [ ] 多租户隔离：`TenantLineInnerInterceptor`（P1.4）
- [ ] 最小前端（P1.5：登录 / 任务工作台 / 列表 / 详情）
- [ ] 完整联调验收（P1 完成定义）：gateway + tenant + agent-core + tool + 前端

## 3. P1 环境前提

- 本机中间件：Nacos ✅ / RabbitMQ ✅ / MySQL ✅ / Redis ✅（MinIO P2 前启动）
- Nacos dev 命名空间已就绪，占位配置已发布
- 密钥：本地 `.env`（不入库）设置 `FINAUDIT_MODEL_API_KEY`
