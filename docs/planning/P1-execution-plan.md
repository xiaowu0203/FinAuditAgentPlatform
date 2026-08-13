# P1 核心闭环 · 执行点文档（占位）

> 版本: v0.1 ｜ 状态: **待设计** ｜ 前置依赖: P0 已完成
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

## 2. 待设计执行点（新会话进行）

> 按项目约定：P1 开工前先设计执行点并经用户确认，再动手。

- [ ] 数据库设计：tenant/user/task/tool 等核心表 + 建库脚本
- [ ] `common-model-starter` DeepSeek 实现（Spring AI core 接入）
- [ ] `agent-core-service`：任务状态机、MQ 消费/生产、持久化
- [ ] `tool-service`：工具注册表与执行器
- [ ] `tenant-service`：租户/用户/权限 + JWT
- [ ] 网关路由规则 + 鉴权过滤
- [ ] 最小前端（任务提交 + 结果展示）
- [ ] 端到端联调验收：提交任务 → Agent 执行（含工具）→ 结果落库

## 3. P1 环境前提

- 本机中间件：Nacos ✅ / RabbitMQ ✅ / MySQL ✅ / Redis ✅（MinIO P2 前启动）
- Nacos dev 命名空间已就绪，占位配置已发布
- 密钥：本地 `.env`（不入库）设置 `FINAUDIT_MODEL_API_KEY`
