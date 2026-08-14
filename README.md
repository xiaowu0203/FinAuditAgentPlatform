# FinAuditAgentPlatform · 财务费用智能审核 Agent 平台

> **当前进度：P0 基建 ✅ ｜ P1 核心闭环 ✅（P1.5e 五服务联调验收通过）｜ P2 规划中** · 详细文档见 `docs/`

## 项目简介

基于 Spring Cloud 分布式微服务 + Spring AI 的**财务费用智能审核 Agent 平台**：
将报销单/费用单据的审核从「人工逐单审核」升级为「Agent 自主拆解 → 分步执行 → 工具联动 → 自主纠错 → 高风险人工审批」的智能化闭环，支持多租户、可量化评估、全链路可观测。

**核心差异化**：不是问答机器人，而是具备任务断点续跑、Multi-Agent 异步协作、分层上下文治理、人工审批网关与审计留痕的工程化 Agent 平台。

## 功能概览

### 已落地（P1）

- **Agent 任务闭环**：任务持久化 + 状态机（`PENDING → RUNNING → SUCCESS / FAILED`）+ 断点续跑 + RabbitMQ 异步编排
- **模型接入**：DeepSeek（Spring AI），任务规划 prompt 动态注入工具目录
- **多租户 + 鉴权**：租户数据逻辑隔离 + RBAC + JWT 会话作废（Redis 黑名单）
- **工具治理**：工具注册表 + 入参 Schema + 动态上下线，首个落地工具 `amount_verify`（金额一律 Decimal）
- **服务间契约**：Feign 契约统一进 common-code + common-feign-starter 请求头/token 透传
- **最小前端**：Vue3 + TypeScript 四页面（登录 / 工作台 / 任务列表 / 任务详情）

### 规划中（P2+，见 `docs/planning/future-roadmap.md`）

- **P2** 报销单闭环（图片上传 + MinIO）、OCR / 预算 / 规则 / 重复检测四类审核工具、财务规则可视化配置
- **P3** 多 Agent 协同流水线（规则引擎驱动）+ 审批工单 + 人机协同审计留痕
- **P4** RAG 企业知识库（Milvus）、监控大盘与量化评估

## 技术栈

JDK 21 · Spring Boot 3.5.0 · Spring Cloud 2025.0.0 · Spring Cloud Alibaba 2025.0.0.0 · Spring AI 1.1.2 · MyBatis-Plus 3.5.12 · MySQL 5.7 · Redis · Nacos 3.2.2 · RabbitMQ · MinIO · Milvus（P2）· Vue3

## 当前状态

| 阶段 | 状态 | 内容 |
|---|---|---|
| P0 基建 | ✅ 完成 | 多模块骨架、common × 8、网关注册 Nacos、双 remote 同步、Nacos 初始化 |
| P1 核心闭环 | ✅ 完成 | 任务状态机 + 断点续跑 + MQ 编排 + DeepSeek + 多租户鉴权 + 最小前端，五服务联调验收通过 |
| P2 单据闭环与审核工具 | 🚧 规划中 | 执行点已设计（`docs/planning/P2-execution-plan.md`），待定 D4/D5 后开工 |

## 快速启动

```bash
# 1. 配置环境变量（密钥不入库）
cp .env.example .env
#    .env 中设置 FINAUDIT_MODEL_API_KEY（DeepSeek）、FINAUDIT_JWT_SECRET

# 2. 初始化 Nacos（dev/test 命名空间 + 占位配置）
bash docs/deploy/nacos-init.sh

# 3. 初始化数据库（finaudit 库 + 种子数据）
mysql -uroot -p < docs/database/finaudit-schema.sql

# 4. 启动后端服务（依赖本机中间件：Nacos / RabbitMQ / MySQL / Redis）
cd backend
mvn spring-boot:run -pl agent-gateway       # 网关 9080
mvn spring-boot:run -pl agent-core-service  # Agent 调度 9201
mvn spring-boot:run -pl tool-service        # 工具执行 9202
mvn spring-boot:run -pl tenant-service      # 租户鉴权 9203

# 5. 启动前端（开发代理 /api → 网关 9080）
cd frontend && npm install && npm run dev   # http://localhost:5173
```

种子账号：`admin / admin123`（租户 `default`，角色 `admin`）。

> 详细环境说明见 `docs/deploy/`，接口文档见 `docs/api/`，架构见 `docs/architecture/`，分期规划见 `docs/planning/`。

## 目录结构

见 `CLAUDE.md` 第 4 节。

## 开源协议

Apache License 2.0
