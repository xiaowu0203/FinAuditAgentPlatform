# FinAuditAgentPlatform · 财务费用智能审核 Agent 平台

> **当前进度：P0 基建 ✅ ｜ P1 核心闭环 ✅（P1.5e 五服务联调验收通过）｜ P2 单据闭环与审核工具 ✅ ｜ P3a 多 Agent 角色化与规则流水线 ✅ ｜ P3b 审批工单闭环 ✅ ｜ P3c 安全风控 ✅ ｜ P3.5 RBAC 权限体系与部门实体 ✅** · 详细文档见 `docs/`

## 项目简介

基于 Spring Cloud 分布式微服务 + Spring AI 的**财务费用智能审核 Agent 平台**：
将报销单/费用单据的审核从「人工逐单审核」升级为「Agent 自主拆解 → 分步执行 → 工具联动 → 自主纠错 → 高风险人工审批」的智能化闭环，支持多租户、可量化评估、全链路可观测。

**核心差异化**：不是问答机器人，而是具备任务断点续跑、Multi-Agent 异步协作、分层上下文治理、人工审批网关与审计留痕的工程化 Agent 平台。

## 功能概览

### 已落地（P1 核心闭环）

- **Agent 任务闭环**：任务持久化 + 状态机（`PENDING → RUNNING → SUCCESS / FAILED`）+ 断点续跑 + RabbitMQ 异步编排
- **模型接入**：DeepSeek（Spring AI），任务规划 prompt 动态注入工具目录
- **多租户 + 鉴权**：租户数据逻辑隔离 + RBAC + JWT 会话作废（Redis 黑名单）
- **工具治理**：工具注册表 + 入参 Schema + 动态上下线，`amount_verify` 金额核验 + P2b 四类审核工具（`ocr_extract` / `budget_query` / `rule_check` / `duplicate_check`，金额一律 Decimal）
- **服务间契约**：Feign 契约统一进 common-code + common-feign-starter 请求头/token 透传
- **前端**：Vue3 + TypeScript（登录 / 工作台 / 任务列表 / 任务详情 / 报销单提交·列表·详情 / 规则配置）

### 已落地（P2 单据闭环与审核工具）

- **P2a 单据闭环 ✅**：新增 `file-service`（9205）承载纯文件资源（上传/下载/预览，唯一持有 `common-oss-starter`）；报销单 CRUD/提交/任务生成/审核流程归属 `agent-core-service`（提交服务内直调建任务，同事务消灭跨服务孤儿窗口；读附件一律 `FileServiceFeign` 远程调用，禁止直连 OSS）；`rag-service`（9204）回归 RAG 专用空骨架（P4 填 Milvus）；前端上传/提交页 + 任务跳报销单已落地
- **P2b 审核工具做厚 ✅**：OCR / 预算查询 / 规则校验 / 重复报销检测四类工具（金额全 Decimal），工具注册 + JSON Schema 校验，报销闭环两轮验证通过
- **P2c 财务规则配置 ✅**：`finance_rule` 表 + 配置页 + Nacos 动态刷新（`TenantNacosConfigHelper` 监听 + 缓存 TTL + DB 降级），改规则不发布服务即时生效；同租户同类型唯一约束

### 已落地（P3a 多 Agent 角色化与规则流水线）

- 五类财务 Agent 角色：文档解析、预算核算、规则校验、风控审计、审批调度
- `REIMBURSEMENT` 任务使用固定规则流水线，输出 `AUTO_PASS` / `NEED_REVIEW` 分支及复核原因
- `APPROVAL_PENDING` 表示待人工复核，命中触发条件（大额 / 超标 / 风控存疑）或 LLM 非通过时暂停进入审批态

### 已落地（P3b 审批工单闭环）

- **审批工单状态机**：`audit_ticket` 全生命周期（PENDING → APPROVED / REJECTED / TERMINATED / AMENDED / WITHDRAWN），`audit_record` append-only 留痕（操作人 / 前后金额 / 意见 / 前后数据快照）
- **财务审批动作**：`approve` 通过 / `reject` 驳回 / `terminate` 终止（Redisson 锁 `audit:ticket:{id}` + 锁内重读防并发覆盖）
- **提交人修改重跑（resubmit）**：改明细后同单续跑（上限 3 次，title/deptName 服务端锁定），重跑 `AUTO_PASS` 自动闭合 / 再命中复位 PENDING / 失败 `onRerunFail` 防死端
- **撤回 / 撤销**：PENDING 提交人直接撤回；APPROVED 后发起撤销（`WITHDRAW_PENDING`）→ 财务同意作废 / 拒绝回退
- **可见性统一**：财务看本租户全量、普通用户仅看本人的工单 / 单据 / 任务

**审批工单完整流转（状态机）**

![审批工单状态机（整页：图 + 三层联动表 + 动作矩阵）](docs/images/audit-ticket-workflow.png)

> 提交后流水线自动判级：`AUTO_PASS` 直接闭环无工单；`NEED_REVIEW` 生成工单进入人工审批。修改重跑从 `AMENDED` 出发有三去向，其中「再命中 / 失败」都复位 `PENDING`（RERUN / RERUN_FAILED 留痕）。详见 `docs/architecture/task-orchestration.md`。

### 已落地（P3c 安全风控）

- **Prompt 注入拦截**：common-code `PromptInjectionGuard`（正则 + 可配置规则）在 `AgentOrchestrator.executeLlmStep` 拼接 user 后、调 LLM 前校验；命中**不调 LLM**，合成 `uncertain=true/confidence=0` 的 `RiskAssessment` → `ReviewFlowDecider` 判 `RISK_HIT` → 建审批工单**强制人工**（命中不直接放行）
- **输出脱敏 `@Mask`**：common-code Jackson 序列化切面，对外 VO 按 `MaskType`（ID_CARD/BANK_CARD/TAX_NO/PHONE）脱敏；手机号（租户用户 VO）、税号（`AttachmentVO.ocrResult` Map）、审计快照敏感键均生效，**金额永不脱敏**；内部链路明文
- **工具防越权**：tool-service `ToolAccessGuard` 在 `execute` 统一入口校验租户一致性 + 部门校验（空白拒绝，未知部门告警不阻断）+ 单据归属（`duplicate_check`/`ocr_extract`），覆盖 HTTP 直调与 MQ

### 已落地（P3.5 RBAC 权限体系与部门实体）

- **资源级权限码（RBAC）**：`sys_permission`/`sys_role_permission` 权限码体系，接口统一 `@RequirePerm` 收口（403 fail-closed）；网关改读 Redis 权限快照（角色/权限/部门权威来源），变更经事件在事务提交后刷新，**改权限无需重新登录**
- **部门实体**：`sys_dept` 树形部门（防环 + 删除引用守卫），用户/报销单/预算挂 `dept_id`；`budget_query` 工具按部门归属收紧
- **管理前端**：权限码驱动渲染三层封装（路由守卫 → 菜单 → `v-perm` 指令），系统管理三页（用户/角色/部门），报销部门选择器；403 自动刷新权限，前后端实时收敛
- **安全加固（P3.5c/d）**：工具注册/调试挂权限码、文件归属校验（堵 IDOR）、网关 actuator 收口（移除 gateway 端点暴露 + 白名单收窄至 health）、模型/OCR HTTP 超时接线、任务级超时预算（`started_at`）、状态迁移 CAS 化（多实例防线）、登录失败锁定（5 次锁 15 分钟）+ 账号存在性泄露修复

### 规划中（P4，见 `docs/planning/future-roadmap.md`）

- **P4** RAG 企业知识库（Milvus）、定时任务（task-job-service）、监控大盘与量化评估

## 技术栈

JDK 21 · Spring Boot 3.5.0 · Spring Cloud 2025.0.0 · Spring Cloud Alibaba 2025.0.0.0 · Spring AI 1.1.2 · MyBatis-Plus 3.5.12 · MySQL 5.7 · Redis · Nacos 3.2.2 · RabbitMQ · MinIO · Milvus（P4 引入）· Vue3

## 当前状态

| 阶段 | 状态 | 内容 |
|---|---|---|
| P0 基建 | ✅ 完成 | 多模块骨架、common × 8、网关注册 Nacos、双 remote 同步、Nacos 初始化 |
| P1 核心闭环 | ✅ 完成 | 任务状态机 + 断点续跑 + MQ 编排 + DeepSeek + 多租户鉴权 + 最小前端，五服务联调验收通过 |
| P2a 单据闭环 | ✅ 完成（后端 + 前端） | file-service（9205）纯文件 + agent-core 报销域（服务内直调建任务）+ rag-service 回归 RAG 空骨架，编译/单测/E2E 通过；前端上传/提交页 + 任务跳报销单已落地 |
| P2b 审核工具做厚 | ✅ 完成 | OCR/预算/规则/重复检测四类工具（金额全 Decimal），工具注册 + JSON Schema 校验 + 报销闭环两轮验证通过 |
| P2c 财务规则配置 | ✅ 完成 | finance_rule 表 + 配置页 + Nacos 动态刷新（`TenantNacosConfigHelper` 监听 + 缓存 TTL + DB 降级），改规则不重启即时生效；同租户同类型唯一约束 |
| P3a 多 Agent 角色化 | ✅ 完成 | 五类财务角色 + `RuleBasedFlowEngine` 固定流水线 + `ReviewFlowDecider` 输出 `AUTO_PASS`/`NEED_REVIEW` |
| P3b 审批工单闭环 | ✅ 完成 | 工单状态机 + `audit_record` 留痕 + 财务审批 + 提交人 resubmit 修改重跑 + 撤回/撤销 + 可见性统一 + 前端审批工单页 |
| P3c 安全风控 | ✅ 完成 | Prompt 注入拦截（命中→强制人工工单） / `@Mask` 输出脱敏（税号/手机号，金额明文） / 工具 execute 统一越权校验 |
| P3.5 RBAC 与部门 | ✅ 完成 | 资源级权限码 + `@RequirePerm` 统一鉴权 + 权限快照实时生效 + `sys_dept` 部门实体贯穿用户/报销/预算 + 管理前端三页（权限码驱动渲染）+ P3.5c/d 安全加固（工具/文件权限码、actuator 收口、模型超时、任务 CAS 与超时、登录防爆破） |

## 快速启动

```bash
# 1. 配置环境变量（密钥不入库）
cp .env.example .env
#    .env 中设置 FINAUDIT_MODEL_API_KEY（DeepSeek）、FINAUDIT_JWT_SECRET；百度 OCR AK/SK 见 .env.example（FINAUDIT_OCR_BAIDU_*）

# 2. 初始化 Nacos（dev/test 命名空间 + 占位配置）
bash docs/deploy/nacos-init.sh

# 3. 初始化数据库（finaudit 库 + 种子数据）
mysql -uroot -p < docs/database/finaudit-schema.sql

# 4. 启动后端服务（依赖本机中间件：Nacos / RabbitMQ / MySQL / Redis / MinIO）
cd backend
mvn spring-boot:run -pl agent-gateway       # 网关 9080
mvn spring-boot:run -pl agent-core-service  # Agent 调度 + 报销单 9201
mvn spring-boot:run -pl tool-service        # 工具执行 9202
mvn spring-boot:run -pl tenant-service      # 租户鉴权 9203
mvn spring-boot:run -pl rag-service         # RAG 基础设施（P4 填充）9204
mvn spring-boot:run -pl file-service        # 文件资源（上传/下载/预览）9205

# 5. 启动前端（开发代理 /api → 网关 9080）
cd frontend && npm install && npm run dev   # http://localhost:5173
```

种子账号：`admin / admin123`（租户 `default`，角色 `admin`）。

> 详细环境说明见 `docs/deploy/`，接口文档见 `docs/api/`，架构见 `docs/architecture/`，分期规划见 `docs/planning/`。

## 目录结构

见 `CLAUDE.md` 第 4 节。

## 开源协议

Apache License 2.0
