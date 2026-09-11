# 业务闭环与 Agent 能力补强 · 重构执行点文档（P3.8）

> 版本: v2.0 ｜ 状态: **待评审** ｜ 分支: `refactor/agent-autonomy-hardening`（基于 main `8eca9f8`）
> 前置: P3.5 已合并；本分支不引入新业务域，做三件事——**业务闭环补齐（预算真实占用 / 按票查重 / 票据-明细交叉核验 / 驳回引导）+ Agent 能力补强（语义自校验纠错）+ 主链路止血与文档一致性**。
> 目标阶段对应: 原 P4「可观测与评估」之前插入本阶段（记作 **P3.8**），因为本阶段产出正是 P4 指标的**数据源**。
> 配套评审材料: 两轮全量代码走查 + 一轮业务目标对齐走查（见 §3 症状表，每条含文件:行号证据）。
> **v2.0 修订要点**：按业务目标走查结果重排优先级——**业务闭环（R1~R4）提到 Agent 能力（R5~R6）之前**；新增 B 类症状（业务闭环缺口 10 项）；确认 6 项决策。

---

## 1. 背景：为什么要重构

### 1.1 现状判断

两轮全量走查（后端 260 个 Java 文件 + 前端 21 个 src 文件 + 全套文档）后的结论：

- **工程实现水平明显高于对外叙事**：CAS 状态迁移、事务感知分布式锁（`DistributedLockTemplate.executeInTx`）、MQ `afterCommit` 发布、迟到结果白名单、重规划的唯一键设计（`uk_task_step` 含 `deleted`），都是**真实故障驱动**出来的代码，注释里留着复现记录。
- **但存在系统性"收尾漏两成"**：同一个加固动作做了八成、漏了两成，且漏掉的往往是**主链路**而非边角。

### 1.2 四个必须解决的结构性问题

| # | 问题 | 本质 |
|---|---|---|
| A | **业务闭环有实质缺口（最高优先）**：预算 `used_amount` **从未写入**（只是只读预检）、发票号被丢弃导致查重判据错误、票据金额与报销明细**从不交叉核验**、驳回原因未结构化为可行动引导 | 决定"这个系统业务上能不能真用"。**Agent 能力是它的放大器，不能替代它**——自校验若没有业务核验产出的真实数据，断言全是空转 |
| B | **Agent 自主性证据链被削弱**：报销主链路是 `RuleBasedFlowEngine` 硬编码流水线，LLM 只做 2 个语义节点；"自主拆解"只剩 `GENERIC` + `TaskPlanner` 这条**无产品形态**的遗留通道 | 需求标准③「自主任务拆解 / 分步执行 / 工具联动 / **自主纠错**」中，**自主纠错实质未实现**（只有传输层重试，无语义层自校验） |
| C | **收尾缺口集中在主链路**：P3.5d 给模型与附件下载补了超时却漏了 OCR；P3.5 建了权限码体系却漏了 `/audit/**` 与 `/tools`；P3.5c 给文件加了归属校验却没考虑 Feign 会带用户上下文进来 | 加固缺少**清单式收口**，靠记忆逐个补，必然漏。注意业务缺口 A-1 也属此类（"审核通过后累加"只写了注释没写代码） |
| D | **文档即交付物，但已系统性漂移**：`docs/api/tool-service.md` 存在形状级错误（消费方按文档实现必错）、`task-orchestration.md` 漏记 Schema 校验与越权守卫、P3.5 计划未勾选实际已完成的 R3 | 项目定位是开源交付，文档错误=对外承诺错误 |

### 1.2.1 业务目标达成度（六项目标对齐走查）

| 目标 | 现状 | 缺口性质 | 对应症状 |
|---|---|---|---|
| 自主处理 | 🟡 流水线全自动，但 AUTO_PASS 门槛含 LLM 主观项、无通知、无幂等 | 产品参数 + 缺通知 | B-8, B-9, B-10 |
| 报销单核验 | 🟡 核"单据内部一致性 + 规则"，**不核"票据 vs 明细"** | 缺关键交叉校验 | B-5 |
| 发票真伪查重 | 🔴 查重判据错（商户名替发票号、发票号被丢弃）、整单粒度、误报高；真伪零实现 | 判据重构 + 需外部接口决策 | B-3, B-4, B-6 |
| 预算占用校验 | 🔴 **`used_amount` 从未写入**，是只读预检而非占用，且无释放路径 | 业务闭环最大缺口 | B-1, B-2 |
| 报销进度查询 | 🟢 基本完成（单据/任务/步骤/工单留痕齐全），缺主动通知与进度百分比 | 锦上添花 | B-8 |
| 驳回重提引导 | 🟡 有原因展示 + 重跑闭环（三岔处理干净），缺"定位明细 + 建议值" | 数据齐备、只差加工 | B-7 |

### 1.3 本轮走查新发现的 3 个"事实性修正"（先纠正，避免带着错误前提重构）

1. **根 `README.md` / `README.en.md` 存在**（`git ls-tree HEAD` 确认）。第一轮"缺失"的判断是工具读取时的编码问题（文件正常，是控制台 GBK/UTF-8 显示错乱），**不是项目缺陷**，§3 的 P2 项已删除。
2. **`AGENTS.md` / `CLAUDE.md` 被 `.gitignore:46-47` 排除，未纳入仓库**——这是 6502641 提交的**有意决策**（本地工具文件不入库）。但后果是：**规格说明不在开源仓库里**，克隆者看不到代码规范。本轮不推翻该决策，改为在 `README.md` 增一节"开发规范入口"，把可公开的部分（分层/命名/金额 Decimal/Starter 规范）落到 `docs/architecture/conventions.md`，敏感的策略性内容仍留本地。
3. **main 与双远程完全同步**（`main` = `github/main` = `gitee/main` = `8eca9f8`），无需 pull。

---

## 2. GitHub 同类项目调研

### 2.1 调研范围与结论概览

调研了 Java / Spring 生态、Agent 框架、财务审核垂类三个方向。**核心结论：没有找到与本项目同构的开源项目。**

| 项目 | 技术栈 | 与本项目的关系 | 可借鉴点 |
|---|---|---|---|
| [alibaba/spring-ai-alibaba](https://githublb.vercel.app/repo/alibaba/spring-ai-alibaba) | Java / Spring AI | 同生态，10k Star 级 | 若 P4 真要上 A2A/跨服务 Agent，这是唯一现实选型（本项目 P3 决策已明确"P4 真正跨服务时再上"） |
| [Mr-XX23/Lambda-Agent-Core](https://github.com/Mr-XX23/Lambda-Agent-Core) | Java 原生 Agent 编排引擎 | 最接近的是**编排内核** | "multi-step LLM reasoning + structured JSON outputs + custom tool execution"三件套的接口切分，正好对应本项目 `AiClient.chatStructured` / `ToolRegistryService` |
| [sadreammm/Operant](https://github.com/sadreammm/Operant) | 多租户 SaaS + Agentic 工作流 + MCP + Kafka + Postgres RLS | **架构维度最接近**（多租户 + Agent 工作流 + 异步消息 + 工具调用） | ① 用 **DB 层 RLS** 做租户隔离（本项目是 MP 拦截器拼 `tenant_id`，公信力可对比）；② 工具协议选 **MCP** 而非自研注册表 |
| [IQZZ020501/NexaFlow](https://github.com/IQZZ020501/NexaFlow) | FastAPI + Next.js + 多租户 RAG + MCP | 定位最接近（企业级 AI 原生协作平台） | 多租户 RAG + 工作区级工具集成；但其 Agent 编排深度不如本项目 |
| [Sourish-Kanna/SmartAudit-LLM](https://github.com/Sourish-Kanna/SmartAudit-LLM) | Llama 3 via Groq | **业务最接近**（发票/凭证自主审计） | 结构极简（单模型 + 规则），反向印证本项目"规则引擎 + LLM 语义节点"的取舍方向正确，但**它的卖点是 Agent 自主**，本项目反之 |
| [AlperNab/invoice-ai](https://github.com/AlperNab/invoice-ai) | — | 票据字段抽取 + 校验 | 仅 OCR/抽取层，无审批闭环、无多租户 |
| [xgl1103/AI-Accounting-Agent-Workbench](https://github.com/xgl1103/AI-Accounting-Agent-Workbench) | — | 会计 Agent 工作台 | 前端交互范式参考 |
| [hoccungduy/xClaw](https://github.com/hoccungduy/xClaw) | Gateway 架构 + 多通道 + 可视化 Workflow Builder | 形态不同（面向 C 端多渠道） | **拖拽式 Workflow Builder** 值得参考：本项目流水线是硬编码的，可视化编排是 P5 可选方向 |
| [Zenika/spring-ai-function-calling-demo](https://github.com/Zenika/spring-ai-function-calling-demo) | Spring AI + Ollama | 教学 Demo | 反例：证明本项目"拒绝问答 Demo"的定位有区分度 |

### 2.2 借鉴后的 5 条判断

1. **同构项目不存在 → 本项目最大的资产是"分布式工程 + Agent + 财务垂类"的三重叠加**，这个组合在开源里是空位。但前提是**三重都要立得住**——目前分布式与垂类立得住，Agent 那重最弱。
2. **租户隔离的实现层次值得重新审视**：`Operant` 用 Postgres RLS（数据库层强制），本项目用 MyBatis-Plus 拦截器拼 `tenant_id`（应用层拼串），且**上下文缺失时回退租户 1 并 WARN**（`CommonMybatisPlusAutoConfiguration.java:84-93`）。这是"更宽容"的设计，在开源项目里会被质疑。应至少补一层**集成测试断言**证明不泄露。
3. **工具协议 MCP 化是行业趋势**，但本项目自研注册表 + JSON Schema 已够用，**不建议本轮改**（收益低、风险高）。登记 P5 评估。
4. **可视化 Workflow Builder 是差异化加分项**，但不解决当前问题，登记 P5。
5. **没有任何同类项目把"人工审批闭环 + 审计留痕"做到本项目这个深度**——这是本项目真正的护城河，文档与演示应以它为主叙事之一，而不是只讲多 Agent。

---

## 3. 症状表（两轮走查全量归集，含证据）

> 分类：**C**=正确性缺陷 / **S**=安全 / **A**=Agent 能力 / **D**=文档漂移 / **Q**=工程质量

### P0 · 主链路正确性/可用性（必须本阶段修完）

| ID | 类 | 症状 | 证据 | 影响 |
|---|---|---|---|---|
| P0-1 | C, 性能 | **OCR HTTP 超时是死配置**：`OcrProperties.Baidu.timeoutMs` 被读入但从未用于构造 `RestClient`，`getTimeoutMs()` 全仓无调用 | `OcrProperties.java:29`、`BaiduOcrService.java:89-97/459-461` | 配合 `concurrency=1`，一次 OCR 网络挂起 → **整条 TOOL 消费线程无限期冻结 → 全租户全部任务停止推进**。P3.5d 已给模型与附件下载补超时，唯独漏此处 |
| P0-2 | C | **`tool_execution_log.input_params` NOT NULL 与可变 null 入参冲突**：DDL 非空无默认值，`ToolExecutionLog.from` 直接写可为 null 的 `msg.inputParams()`；异常发生在 catch 分支内 | `finaudit-schema.sql:202`、`ToolExecutionLog.java:68`、`ToolExecutionService.java:71` | 失败分支写日志抛异常 → **既不回 `tool.result` 也不进 DLQ 的正常路径**，步骤永久停在 RUNNING，只能等 30 分钟任务超时或人工 resume |
| P0-3 | C | **file-service 归属校验与 agent-core 附件展示互相打架**：`requireReadable` 以"有无用户上下文"区分内外链路，但 `FeignHeaderPropagator` 在 HTTP 请求线程上会透传 `X-User-Id` | `FileService.java:129-141`、`FeignHeaderPropagator.java:22-33`、`AttachmentService.java:162-172` | 用户 A 打开同租户用户 B 的报销单 → 附件区**静默空白**（`fetchFiles` 静默返回空 Map），无任何错误提示 |
| P0-4 | C, 前端 | **编辑用户静默清空全部角色**：`openEdit` 把 `roleIds` 置 `[]` 且从不回填，保存时无条件 `assignUserRoles` | `system/user.vue:79,111`；`api/system.ts:31` 的 `getUserDetail` 全项目 0 调用 | 管理员"只改手机号"= 清空该用户所有角色 |
| P0-5 | C, 前端 | **403 → 权限实时收敛是死代码**：响应拦截器已解包 `body.data`，403 分支又按未解包结构取 `resp.data` | `api/request.ts:36` vs `:51` | P3.5 验收项 4（"移除权限后菜单+接口即时收敛"）**未真正生效**；`stores/auth.ts:53`、`directives/perm.ts:16` 的承诺落空 |
| P0-6 | S | **`/api/v1/audit/**` 数据面缺权限码与归属校验**：6 个端点仅校验 `X-Tenant-Id` 非空；`POST /attachments/{id}/ocr-result` 是**写操作** | `AuditDataController.java:48-108`、网关路由 `application.yml:72-75` | 任意登录用户可覆盖本租户任意附件的 OCR 结果（**伪造票据识别数据，直接影响审核结论**）；`GET /reimbursements/{id}/tenant` 可跨租户探测单据存在性 |
| P0-7 | S | **`/api/v1/tools` 列表与调试直调缺权限码**：`tool:manage`/`tool:execute` 已种下但 `list` 无任何注解，仍用 `X-Tenant-Id` 手写解析 + `defaultValue="1"` | `ToolController.java:40-45`；`finaudit-schema.sql:462-463` | 任意登录用户可读全量工具目录（含 `input_schema`）；直连 9202 可读默认租户数据 |
| P0-8 | C | **tenant-service `deptId=0 解绑`不生效**：`SysUser.apply` 把 0 转 `null`，`updateById` 在默认 `NOT_NULL` 策略下跳过 null 字段，yml 未覆盖 `update-strategy` | `SysUser.java:94-97`、`SysUserService.java:118`、`UserUpdateRequest.java:11` | 前端认真传 0（`system/user.vue:107`），后端静默忽略 → 部门解绑功能实际不可用 |
| P0-9 | C | **`AuditDataController`/`ToolController` 的 `defaultValue="1"`**：绕过网关直连即静默落入租户 1 | `ToolController.java:43/52/61`、`FileController.java:39` | 直连场景的租户归属错误 |

### P1 · 让既有机制真正生效

| ID | 类 | 症状 | 证据 |
|---|---|---|---|
| P1-1 | S | **`ToolAccessGuard` 租户一致性校验在生产路径恒等通过**（MQ 侧与 HTTP 侧的"上下文租户"与"声明租户"同源） | `ToolAccessGuard.java:57-62`、`ToolExecuteConsumer.java:34`、`ToolController.java:43/61` |
| P1-2 | A, 可观测 | **Token 用量统计无任何出口**：`usageSnapshot()` 仅测试调用，注释自述"P2 落库"未做；多模型 failover 机制因只注册 DeepSeek + `fallbackType` 缺省 null 而空转 | `DefaultChatClientFactory.java:43-47`、`CommonModelAutoConfiguration.java:29-33`、`ModelProperties.java:27-28` |
| P1-3 | Q | **MQ 无 publisher confirm；DLQ 无消费者无告警**；`acknowledge-mode=auto` 且无重试拦截器 | 两服务 yml `36-46`、`CommonMqAutoConfiguration.java:62-64` |
| P1-4 | S, C | **工具缓存 Key 不含租户且读写无容错** | `ToolExecutionService.java:96-107,137` |
| P1-5 | S | **`tool:manage` 可自行削弱 Schema 校验**（注册时直接落 `request.inputSchema()`，不校验强度） | `ToolRegistryService.java:56-72` |
| P1-6 | C | **Schema 与执行器语义相反**：`budget_query` 的 DB schema 仍必填 `deptName`，而执行器已支持"只给 deptId"——**P3.5b 刚确立 deptId 为权威键，Schema 却在拦它** | `finaudit-schema.sql:497`、`BudgetQueryTool.java:53` |
| P1-7 | Q, 安全 | **环境变量回退默认值硬编码**：Nacos `nacos/nacos`、RabbitMQ `guest/guest`、MinIO `minioadmin/minioadmin123`；且 RabbitMQ 默认值与 `.env.example` 的 `admin/admin123456` 不一致 | 各服务 `application.yml`；`ObjectStorageProperties.java:10` 注释自称"凭据禁止入库" |
| P1-8 | C | **角色绑定不校验 roleId 归属/存在性**；批量 ID 不去重 → `PUT /roles/{id}/permissions {"permIds":[1,1]}` 撞 `uk_role_perm` 报 400 | `SysUserRoleService.java:49-62`、`SysPermissionService.java:132` |
| P1-9 | C | **Feign 全链路无超时配置**（全后端 yml 无 `feign.client.config`） | grep 全后端 |
| P1-10 | C | **`AgentTaskService.requireVisible` null 处理与 `AuditTicketService` 不一致**（`userId == null` 且 `createdBy != null` 时 NPE→500）；形参名仍为已废弃语义 `finance` | `AgentTaskService.java:134` vs `AuditTicketService.java:265` |
| P1-11 | C | **部门写操作无 `@Transactional` 且直接返回实体 `R<SysDept>`**；`SysDeptService.create` 手写 set 组装实体 | `SysDeptController.java:52/60`、`SysDeptService.java:137-142` |
| P1-12 | C | **上传大小限制只存在于前端文案**：`create.vue:306` 写"≤20MB"但无 `before-upload`；`FileService.upload` 无尺寸校验 | `reimbursement/create.vue:306`、`FileService.java:69-90` |
| P1-13 | Q | **`FileService.validateAllOwned` 是死代码**且自身无归属校验；`file-service` 无删除接口 → 孤儿对象无回收路径 | `FileService.java:149-159`、grep `deleteObject` |
| P1-14 | Q | **`/actuator/info` 在白名单外**（yml 注释称"仅保留 health/info 做存活探测"）；**网关 Swagger 白名单是死分支**（pom 无 springdoc、无对应路由谓词） | `AuthGlobalFilter.java:252`、`agent-gateway/pom.xml` |

### P2 · Agent 能力补强（本阶段核心，见 §4）

| ID | 类 | 症状 |
|---|---|---|
| A-1 | A | **自主纠错实质未实现**：只有 TOOL 步骤传输层重试 3 次（即时重发无退避），无语义层"结果合法性与交叉一致性校验 → 异常自动重执行"（需求标准③3） |
| A-2 | A | **`GENERIC` + `TaskPlanner` 是真·LLM 规划，但无产品形态**：只有工作台一个 JSON 文本框能触发，无审批闭环、无业务语义、`task_type` 默认值即是它 |
| A-3 | A | **多 Agent 协作与需求表述有落差**：5 个 `AgentRole` 全在 agent-core 进程内角色化，**不满足"Agent 之间通过消息队列异步通信"**；文档表述未明确澄清 |
| A-4 | A | **`RuleBasedFlowEngine` 硬编码**：步骤顺序与条件写在 Java 里，与项目"规则可配置（`finance_rule` + Nacos 动态刷新）"的既有能力风格不一致 |
| A-5 | A | **工具结果契约不统一**：`tool-service` 各执行器返回自由 `Map`，与 `ToolRegistryService` 的 `input_schema` 强校验形成非对称——**入参有 Schema，出参没有** |

### P3 · 文档漂移（开源交付物，必须本阶段同步）

| ID | 位置 | 漂移 |
|---|---|---|
| D-1 | `docs/api/tool-service.md:60` | **`ocr_extract` 结果形状完全错误**（文档 `{ocrStatus,amount,date,merchant,taxNo}`，实际 `{reimbId,receipts[],successCount,failedCount,message}`，字段在 `receipts[].fields`） |
| D-2 | `docs/api/tool-service.md:63` | **`duplicate_check` 字段名与类型双错**（`suspected:[{...}]` vs 实际 `suspected:boolean` + `duplicates[]`） |
| D-3 | `docs/api/tool-service.md:61` | `budget_query` 漏 `configured/claimedAmount/exceedsBudget/message`，且未配置时**无 `remaining`** |
| D-4 | `docs/api/README.md:13`、多处 `:43` | "返回 400"实为 **HTTP 200 + body `code=400`**；全局语义未澄清（前端按 HTTP 状态分支会全走成功分支） |
| D-5 | `docs/api/tenant-service.md` | **漏记 3 个已实现端点**（`GET /permissions`、`GET|PUT /roles/{id}/permissions`）；登录响应漏 `perms`；`UserVO`/`UserDetailVO` 漏 `deptId/deptName`；创建用户漏 `deptId`；**只给部门写了权限码**，用户/角色/租户/权限目录各章一字未提 |
| D-6 | `docs/api/file-service.md` | **完全未记录 P3.5c 归属校验规则**；`:27` 批量语义描述与实现不符；`:35` `Content-Disposition` 位置描述含糊；未声明 `X-Tenant-Id` 默认值 1 |
| D-7 | `docs/architecture/task-orchestration.md:41` | 流水线顺序失真（文档 `rule_check/amount_verify` 同阶段，实际 `amount_verify` 在前，`RuleBasedFlowEngineTest:31-36` 有断言） |
| D-8 | `docs/architecture/task-orchestration.md:58` | 工具执行链**漏掉 Schema 校验与越权守卫**（安全语义被低估）；`:77,:176` 仍写"财务动作（X-User-Roles 含 admin/auditor）"，实际已是 `@RequirePerm` + 快照权威 |
| D-9 | `docs/architecture/tenant-auth.md:61,78,80` | 忽略表漏 `sys_permission`；登录校验顺序已被 P3.5d 改为"密码优先、禁用后置"；登录响应漏 `perms` |
| D-10 | `docs/api/tool-service.md:35-43` | 未提 `tool:manage`/`tool:execute` 权限码；注册请求示例未含 `scenario/cacheable` |
| D-11 | `docs/planning/P3.5-execution-plan.md` §5 R3 | 复选框**未勾选**，但 R3（管理前端 + 动态渲染）实际已落地（`views/system/{user,role,dept}.vue` + `v-perm` + `meta.perm` 均存在） |
| D-12 | `docs/api/rag-service.md` | 未说明"经网关不可达"（路由表无 9204） |
| D-13 | `common-ocr-starter/README.md:27,41` | 把死配置 `baidu.timeout-ms` 当作生效配置 |
| D-14 | `common-model-starter/README.md`、`ChatClientFactory.java:7` | 陈旧 TODO（"接入 Spring AI"已完成）；`ToolServiceApplication.java:9` 停留在 P1 单工具时代 |
| D-15 | 仓库根 | `AGENTS.md`/`CLAUDE.md` 被 gitignore → **开源仓库无规格说明**；`rag-service/target/surefire-reports` 残留三个已删除测试报告 |
| D-16 | `docs/deploy/README.md`、`docs/test/README.md` | 仍是"待补充"；`docker-compose.yml` 只挂全量 schema，**增量迁移脚本 `migration-*.sql` 在部署文档里没有执行说明** |

### P4 · 已完成/已纠正（避免重复劳动）

| 项 | 结论 |
|---|---|
| 根 `README.md` / `README.en.md` | **存在**（第一轮误判，已纠正） |
| `AGENTS.md` 未入库 | 有意决策（提交 `6502641`），本轮不推翻，改为补公开版规范入口 |
| main 与双远程 | 完全同步（`8eca9f8`） |
| `common-jwt-starter` / `common-mq-starter` 缺 README | 仍缺，归入 Q 类，本轮补 |

### P5 · 业务闭环缺口（B 类 · 2026 需求对齐走查新增）

> 来源：按"自主处理 / 报销单核验 / 发票真伪查重 / 预算占用校验 / 报销进度查询 / 驳回重提引导"六项目标逐项对码走查。
> **B 类优先级高于 A 类**——它们决定"这个系统业务上能不能真用"，Agent 能力是它的放大器而非替代品。

| ID | 类 | 症状 | 证据 | 影响 |
|---|---|---|---|---|
| **B-1** | C, 业务 | **预算从未被真正占用**：`used_amount` 全仓仅 5 处引用（DDL 默认值 / 实体字段 / 注释 / VO 读取 / 工具读取），**无任何写入点**。注释自述"审核通过后累加（P3 审批流），本阶段只读"，实际未实现 | `finaudit-schema.sql:297`、`Budget.java:17,44`、`BudgetVO.java:16,21`、`BudgetQueryTool.java:89` | 同一部门同月多笔报销全部报"预算充足"、全部可 AUTO_PASS/审批通过，**系统一次都不拦**；`exceedsBudget` 恒基于静态种子值。**这是业务闭环上最大的洞** |
| **B-2** | C, 业务 | **预算占用无释放路径**：即使补上占用，`APPROVED → withdraw-request → withdraw-agree → CANCELLED` 这条既有链路必须能回退占用；驳回/终止/撤回同样需要 | `AuditTicketService.java:503-663`（5 个审批动作均只改状态，不碰预算） | 只做占用不做释放 → 预算被永久吃掉，比不占用更糟 |
| **B-3** | C, 业务 | **发票标识符被丢弃**：`VatInvoiceOcr` 已抽出 `invoiceCode`/`invoiceNum`，但 `OcrExtractTool.normalize` 只保留 `amount/date/merchant/taxNo` 四项 | `VatInvoiceOcr.java:26-28`、`OcrExtractTool.java:271-305` | 发票号是本业务查重的**正确唯一键**，丢掉它是纯损失；后续所有"按票"能力（查重/验真/一票一报）全部缺依据 |
| **B-4** | C, 业务 | **查重判据错误 + 粒度错误**：实际算法为「同一申请人 + 整单金额**完全相等** + 报销日期 ±30 天 + 比对**第一张附件的 OCR 商户名**」。未用发票号，未做单票粒度比对 | `ReimbursementService.java:500-546`（`queryDuplicates`）、`:555-569`（`firstMerchant`） | ① 同一发票拆进两张不同金额单据 → **查不出**；② 同部门同事同日同额同商户（如都打同一家车）→ **误报**；③ 金额差 1 分即漏检 |
| **B-5** | C, 业务 | **票据与明细无交叉核验**：`amount_verify` 只比"明细之和 vs 申报总额"，OCR 抽出的 `fields.amount` **全程不参与任何校验**；发票日期/商户与报销日期/费用类型亦无交叉校验 | `AmountVerifyTool.java:40-86`（仅 items vs claimedTotal）、`OcrExtractTool` 产出未回灌校验链 | 上传 100 元发票、明细写 800 元，只要明细自洽 → **系统不报错**。这是"核验"最基础的一环 |
| **B-6** | 业务 | **发票真伪零实现**：全仓无任何税局/第三方查验接口调用，`taxNo` 仅作字段抽取用于脱敏与查重 | grep 无查验相关调用 | "真伪"目前只是"识别"，不含"验真" |
| **B-7** | 业务 | **驳回引导未加工**：`review_reasons` 是**原始文本串**（如 `"RULE_FAIL:规则校验超标"`）拼成的 `risk_desc`，不定位到具体明细行/字段，不给出建议值 | `TriggerTypeResolver.buildRiskDesc`、`RuleBasedFlowEngine` 规则命中结构 | 系统已知"北京酒店标准 500/天"且 OCR 到"实际 680/天"，**但不会告诉用户"超标 180，请调整或说明"**；数据齐备，只差加工 |
| **B-8** | 业务 | **无任何主动通知**：无邮件/短信/站内信/Webhook，进度完全依赖前端轮询 | 全仓无通知模块 | "驳回重提引导""进度查询"的最后一公里缺失 |
| **B-9** | 业务 | **重复提交无幂等**：`submit` 无幂等键，前端重复点击/网络重投会建多条单据 | `ReimbursementService.submit`、`ReimbursementController.java:45-49` | 脏数据来源 |
| **B-10** | 产品 | **AUTO_PASS 门槛由 LLM 决定，自动化率不可控**：进入人工有**两条并存路径**——① `ReviewFlowDecider` 五条硬条件；② **最后一步 LLM 的 `decision != APPROVE`**（`ReviewFlowDecider.java:107-111`）。且 `confidence<0.7` 判定仅作用于 `RISK_AUDITOR` 步骤（`:89-105`） | `ReviewFlowDecider.java:42-115` | 实际自动通过率 = 硬条件零命中 **且** LLM 明确 APPROVE；率值未经实测，需在 R5 用小样本跑出基线后再定阈值 |

> 六项目标达成度总览见 §1.2.1。

---

## 4. 重构方案

### 4.1 指导原则

1. **不推翻已验证的取舍**：规则引擎驱动主流水线是正确决策（`P3-execution-plan §决策1b` 已论证），**本轮不把主链路改回 LLM 自由拆解**。
2. **补强而非重写**：Agent 自主性通过"**增加一个真实的自校验纠错环节 + 把 GENERIC 路径产品化**"来解决，而不是把流水线推倒。
3. **加固走清单制**：本轮建立"加固清单"文档（超时/权限/租户/非空/契约五类），后续每次加固按清单逐项核对，消除"漏两成"。
4. **文档与代码同提交**：D 类漂移与对应代码修复在**同一个 commit** 内完成，杜绝再次漂移。

### 4.2 六个重构动作

> 排期原则：**业务闭环（重构一~四）优先于 Agent 能力（重构五）**。原因：预算是地基，且重构五的"自校验断言"需要重构二/三 产出的数据（发票号、票据-明细差额）才有真东西可断——顺序倒过来会做出一个"断言全是空转"的自校验。
> Agent 能力（重构五）是业务闭环的**放大器**：同样的核验结论，由 Agent 自主交叉验证并驱动重跑，才体现"自主纠错"。

#### 重构一（地基）：预算真实占用与释放 —— B-1 / B-2

**现状**：`used_amount` 无任何写入点，只读预检；审批通过只改状态（`AuditTicketService.approve` → `updateStatusByTaskId(SUCCESS)`），不扣预算。

**方案**

```
占用时机（已决策：审批通过时占用，非提交时预占）
  ├─ AUTO_PASS           → finalizeSuccess 内占用（与报销单置 SUCCESS 同事务）
  ├─ 审批 approve        → AuditTicketService.approve 内占用（同事务）
  └─ 其余（NEED_REVIEW 待审期间）→ 不占用，仅靠 budget_query 预检提示

释放时机（全终态覆盖，B-2）
  ├─ 驳回 reject / 终止 terminate → 若已占用则释放（防御式：正常流程未占用）
  ├─ 撤回 withdraw（PENDING）     → 未占用，无需释放
  ├─ 同意撤销 withdraw-agree       → 已 APPROVED 即已占用 → 释放
  └─ 重跑失败 onRerunFail          → 复位 PENDING，保持"未占用"（原 AMENDED 期间也未占用）

并发安全（关键技术点）
  ├─ 累加走**单条原子 SQL**，禁止"读-算-写"：
  │    UPDATE budget SET used_amount = used_amount + #{amt}
  │    WHERE tenant_id=? AND dept_id=? AND period=? AND deleted=0
  │      AND used_amount + #{amt} <= total_budget      ← 超支在 SQL 层拦死
  │    影响行数=0 → 抛 BizException("部门预算不足")，整个事务回滚
  ├─ 预算行不存在（未配置预算）→ 决策：**不阻断**（保持现状语义，仅告警），
  │    因为存量种子只覆盖 4 个部门 1 个周期
  └─ 释放同样原子：SET used_amount = GREATEST(used_amount - #{amt}, 0) 防负数
```

**落点**：新增 `BudgetOccupancyService`（预算占用/释放的唯一写入口，`Budget` 实体数据访问收敛于此，符合 AGENTS.md §5.9）；`AgentOrchestrator`、`AuditTicketService` 经它调用，**不直接持有 `BudgetMapper`**。
**幂等防护**：`budget_occupancy` 记账表（`tenant_id, reimb_id, period, dept_id, amount, status(OCCUPIED/RELEASED), uk(tenant_id, reimb_id)`）——避免 approve 重复消费、释放反向重复扣减。
**验收断言**：① 同一部门预算 10 万，两笔 6 万同时提交审批，**第二笔必须失败**（并发测试）；② approve → `used_amount` 增加；③ approve 后 withdraw-agree → 释放回原值；④ 重复 approve 幂等不重复占用。

#### 重构二：发票标识符入链 —— B-3

- `VatInvoiceOcr` 已有 `invoiceCode`/`invoiceNum`，但 `OcrExtractTool.normalize` 丢弃 → 补进 `normalized` 的 `fields`（`invoiceCode`/`invoiceNum`），落 `expense_attachment.ocr_result`；
- 新增 `expense_attachment` 冗余列？**否**——保持 OCR 结果 JSON 内嵌（与现有设计一致），查询侧改用 `ocr_result->>'$.invoiceNum'`……**MySQL 5.7 的 JSON 提取性能差**（现有 schema 注释已记录"items 仅作存储不参与 WHERE 过滤"）。因此新增**轻量投影表** `invoice_record`：
  `(id, tenant_id, reimb_id, attachment_id, invoice_code, invoice_num, amount, invoice_date, merchant, tax_no, created_at, deleted)`，`uk(tenant_id, invoice_code, invoice_num)` 兜底同票重复入账。
  写入点：`ocr_extract` 回写链路（`AttachmentService.updateOcrResult` 同步或异步投影）。
- **收益**：为重构三（按票查重）与重构四（票据-明细交叉核验）提供唯一键依据；同时天然支持"一票一报"。

> 取舍：不引入独立微服务、不改 MySQL 版本，用一张投影表绕开 5.7 JSON 检索限制，与既有"`items` 只存不查"的设计口径一致。

#### 重构三：按发票号的精准查重 —— B-4

**替换** `ReimbursementService.queryDuplicates` 的当前算法（同申请人 + 整单金额相等 + ±30天 + 商户名比对），改为**两级判据**：

```
一级（硬命中，高置信）：invoice_record 中已存在相同 (invoice_code, invoice_num)
    → 排除当前单据自身、排除 CANCELLED 单据
    → suspected = true，dupLevel = HIGH（同一张票被重复报销）

二级（软提示，低置信，保留但降权）：金额相等 + 日期 ±30 天 + 商户名相同
    → dupLevel = MEDIUM
    （仅作提示，不再单独触发 NEED_REVIEW，避免同部门同日同额误报）
```

- `DuplicateCheckVO` 扩展 `dupLevel`（HIGH/MEDIUM），`ReviewFlowDecider` 改为**仅 HIGH 触发** `RISK_HIT`；MEDIUM 只展示不拦截（这条同时缓解 B-10 的自动化率问题）；
- 粒度从"整单"改为"单票"：同一报销单内**多条明细对应多张票**时逐票比对；
- 文档同步：`docs/api/tool-service.md:63` 的 `duplicate_check` 结果形状（D-2，现状字段名与类型双错）。

#### 重构四：票据-明细交叉核验 + 驳回引导加工 —— B-5 / B-7

**4a 交叉核验**（新增工具 `invoice_match`，纳入 `RuleBasedFlowEngine` 的 `rule_check` 之后）：

| 断言 | 判据 | 命中后果 |
|---|---|---|
| 票据金额 vs 明细金额 | Σ(单票金额) 与对应明细行金额比对，容差 0.01 | 不一致 → `RULE_FAIL` |
| 票据日期 vs 报销日期 | 开票日期 > 报销日期（未来票）/ 超出时效 | `RULE_FAIL` |
| 票据商户 vs 费用类型 | 餐饮类商户名 + `expenseType=TRAVEL` 等语义冲突 | `RULE_FAIL`（弱提示） |
| 发票号重复入账 | 由重构三一级判据覆盖 | `RISK_HIT` |
| 附件缺失 | 有明细但无任何票据 | `RULE_FAIL` |

> 4a 与重构二是**依赖关系**：没有发票号投影，就不能逐票对明细行。

**4b 驳回引导加工**（B-7：数据齐备，只差加工）：
- 把 `review_reasons` 从**原始字符串**升级为**结构化 `ReviewFinding`**：
  `{ code, level, itemIndex, itemName, expected, actual, gap, suggestion }`
- 例：`{"code":"TRAVEL_STANDARD","itemIndex":2,"itemName":"住宿费","expected":500.00,"actual":680.00,"gap":180.00,"suggestion":"住宿标准 500/天，超出 180 元，请调整金额或补充说明"}`
- `audit_ticket.risk_desc` 兼容保留（字符串摘要），新增 `review_findings JSON` 列存结构化明细；
- 前端 `reimbursement/detail.vue` 与 `audit/detail.vue` 增加"**待修正项清单**"区块（定位到明细行 + 期望值/实际值/差额/建议），`edit.vue` 在对应明细行标红并预填建议值；
- **这条直接落地"驳回重提引导"**，且不需要新数据源。

#### 重构五（放大器）：Agent 语义自校验与自主纠错 —— A-1

在 `RuleBasedFlowEngine` 的 LLM 风控之后、结论汇总之前，插入强制的确定性自校验步骤：

```
... → duplicate_check → LLM(RISK_AUDITOR)
    → 【新增】语义自校验 self_check（确定性交叉一致性）
         ├─ 断言集（前四条来自重构三/四 的真实数据，非空转）：
         │   ① amount_verify.match=false 但汇总结论 APPROVE        → 矛盾
         │   ② rule_check.overLimit=true 但风险等级 LOW            → 矛盾
         │   ③ invoice_match 命中不一致 但结论 APPROVE             → 矛盾
         │   ④ duplicate_check dupLevel=HIGH 但 confidence≥0.9     → 矛盾
         │   ⑤ LLM 输出引用了前序步骤中不存在的字段/单据号/发票号  → 幻觉
         ├─ 全部通过 → 继续汇总
         └─ 命中矛盾 → correction_count+1
                       ├─ < 上限（默认 1 次）：重跑"风控语义判断"（增强 prompt 携带矛盾提示）
                       └─ ≥ 上限：NEED_REVIEW，复核原因附"自校验未通过"结构化 findings
    → LLM(SCHEDULER) 结论汇总
```

**为什么这是"自主纠错"而不是又一层规则**：它**消费 LLM 输出并对 LLM 自己下判断**（含幻觉维度），并**驱动重执行**（重跑风控步骤），属 self-consistency/self-reflection 模式；同时天然产出 P4 的三个指标（工具纠错次数、幻觉率、任务完成率）。
**新增落库**：`agent_task.correction_count INT DEFAULT 0`、`agent_task.self_check_result JSON`。

> 技术选型说明（已决策）：**用确定性一致性断言，不用 LLM self-critique**。理由：可单测、可解释、零额外 Token、不引入新不确定性。若后续需要更强"智能感"，可在断言之上叠加一层 LLM 批评作为**增强**（断言仍是兜底）。

#### 重构六：`GENERIC` 产品化 + 流水线声明式化 + 出参契约 —— A-2 / A-4 / A-5

- **`GENERIC` 产品化**：前端新增"智能分析"入口（`views/analysis/create.vue`）→ `POST /api/v1/tasks`（`taskType=GENERIC`）→ 复用任务详情页展示 LLM 自主规划的步骤与结果；`GENERIC` 同样接入重构五的自校验；命中高风险时同样建审批工单（复用 `enterApproval`，`trigger_type` 归 `RISK_HIT`）。定位从"遗留调试通道"升级为"通用智能分析"。
- **`RuleBasedFlowEngine` 声明式化**：步骤定义抽到 `FlowDefinition`（步骤号/类型/工具编码/角色/条件/入参投影），Java 内保留默认定义（不引入 DDL 表，避免过度设计），来源可后续替换为 Nacos。
- **工具出参 Schema**：`tool_registry` 增 `output_schema JSON NULL`，执行器返回后校验（无 schema 跳过），让自校验有**结构依据**。

### 4.3 已确认决策（本轮）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 预算占用时机 | **审批通过时占用**（AUTO_PASS 与 approve 两处）；待审期间不占用，仅预检提示 |
| 2 | 发票真伪方案 | **先做离线规则验真**（免费）；外部查验服务商（官方/商业 API）**登记后置**，因涉及按次付费与商务决策 |
| 3 | `deptId=0 解绑` 修法 | **用 `LambdaUpdateWrapper` 显式 set null**（局部修，不全局改 MyBatis-Plus `update-strategy`） |
| 4 | 自校验技术选型 | **确定性一致性断言**；LLM self-critique 仅作可选增强 |
| 5 | 自动通过率阈值 | 先不动 `confidence<0.7`；**R5 用小样本跑出 AUTO_PASS 基线后**再定；重构三把 MEDIUM 查重降为"仅展示不拦截"是第一步松绑 |
| 6 | 排期次序 | **业务闭环（重构一~四）优先于 Agent 能力（重构五）** |

### 4.4 明确不做（本轮）

| 项 | 原因 |
|---|---|
| 引入外部发票查验服务商 | 需按次付费与商务决策；本轮只做离线规则验真（登记后置） |
| 引入 MCP 替换自研工具注册表 | 收益低、风险高；登记 P5 评估 |
| 引入 spring-ai-alibaba / A2A 跨服务 Agent | P3 决策已推到"真正跨服务时"；重构六的声明式流水线是它的前置 |
| 把租户隔离改为 DB 层 RLS | `Operant` 的做法更严格，但 MySQL 5.7 无 RLS；改为**补集成测试断言**（成本可控的等效手段） |
| 可视化 Workflow Builder | 不解决当前问题；登记 P5 |
| 把主报销链路改为 LLM 自由拆解 | 明确反对：规则引擎是正确取舍，问题在"缺少自校验与业务交叉核验"，不在"不够自由" |
| LLM self-critique 作为主方案 | 本轮用确定性断言；self-critique 仅登记为可选增强 |

---

## 5. 执行点分解

> 每轮结束必须：`mvn -q clean install` 通过 + 前端 `npm run build`（`vue-tsc -b`）通过 + 相关单测通过 + 文档同 commit。
> 提交前缀遵循 AGENTS.md §6（`fix: / refactor: / feat: / docs: / test: / build:` + 中文说明）。
> **次序**：R0 止血 → R1~R4 业务闭环 → R5 Agent 放大器 → R6~R7 结构与文档 → R8~R9 可选。

### R0 · 主链路止血（对应 P0-1 ~ P0-9，最高优先）

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R0-1 | OCR 超时接线：`RestClient` 设 connect/read 超时（默认 10s），并让 `getTimeoutMs()` 真正被使用 | `BaiduOcrService.java`、`OcrProperties.java` | 单测：配置 1ms 超时时调用抛出且不阻塞；README 同步 |
| R0-2 | `tool_execution_log` 非空冲突：① 先加集成测试复现 ② 修正 `ToolExecutionService` 保证**任何失败路径都必回 `tool.result`**（日志写入 try/catch 降级 + WARN） | `ToolExecutionService.java`、`ToolExecutionLog.java` | 单测：入参为 null 时仍发布 `tool.result(success=false)` |
| R0-3 | file-service 内外链路区分：引入显式内部调用标识（专用内部头 + 服务间校验，或把内部读端点收敛到 `/internal/files/**` 并从网关路由排除） | `FileService.java`、`FileController.java`、网关 `application.yml`、`FeignHeaderPropagator.java` | 集成测试：A 看 B 的报销单能看到附件；A 直接猜 fileId 仍 403 |
| R0-4 | 前端用户编辑回填角色（启用已存在但零调用的 `getUserDetail`） | `system/user.vue` | 手动：改手机号保存后角色不变 |
| R0-5 | 前端 403 权限刷新修复（拦截器已解包 `body.data`，勿再取 `resp.data`） | `api/request.ts` | 手动：移除某权限后 403，菜单/按钮即时收敛 |
| R0-6 | `/audit/**` 权限收口：内部工具端点收敛到 `/internal/**` + 来源校验；`writebackOcrResult` 增加附件归属校验 | `AuditDataController.java`、网关路由 | 普通用户调 `/api/v1/audit/**` 写端点 → 403 |
| R0-7 | `/tools` 权限收口：`list` 挂 `tool:manage`；三端点改用 `UserContextHolder` 取租户，去掉 `defaultValue="1"` | `ToolController.java` | 无 `tool:manage` → 403；无租户头 → 拒绝而非落租户 1 |
| R0-8 | `deptId=0 解绑`：**用 `LambdaUpdateWrapper` 显式 set null**（已决策，不全局改 `update-strategy`） | `SysUserService.java`、`SysUser.java` | 单测：传 0 后 `dept_id` 为 null |
| R0-9 | 拆除全部 `X-Tenant-Id` 的 `defaultValue="1"`，改为上下文缺失即拒绝（fail-closed） | `ToolController`、`FileController`、`AuditDataController` | 无租户头直连 → 明确拒绝 |

### R1 · 预算真实占用与释放（B-1 / B-2，业务地基）

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R1-1 | `budget_occupancy` 记账表 + 迁移脚本（`uk(tenant_id, reimb_id)` 保幂等） | `migration-P3.8.sql`、`docs/database/tables.md` | 迁移幂等可重入 |
| R1-2 | `BudgetMapper` 新增两条原子 SQL（占用带超支条件、释放带 `GREATEST` 防负数），XML 写法 | `BudgetMapper.java`、`mapper/BudgetMapper.xml` | 单测：并发 20 线程各占 6 万 / 预算 10 万 → 仅 1 笔成功 |
| R1-3 | 新增 `BudgetOccupancyService`（占用/释放唯一写入口，`Budget` 数据访问收敛于此） | 新增 Service | 符合 AGENTS.md §5.9；`AgentOrchestrator`/`AuditTicketService` 不持有 `BudgetMapper` |
| R1-4 | 接入占用：`finalizeSuccess`（AUTO_PASS）与 `AuditTicketService.approve` | `AgentOrchestrator.java`、`AuditTicketService.java` | approve → `used_amount` 增加；重复 approve 不重复占用 |
| R1-5 | 接入释放：`withdraw-agree`、`terminate`、`reject`（防御式）、`onRerunFail` | `AuditTicketService.java` | approve 后 withdraw-agree → `used_amount` 回到原值且不为负 |
| R1-6 | 预算不足的失败语义：SQL 影响行数 0 → `BizException`，按业务语义归入 `RULE_FAIL`（进审批而非硬失败） | `BudgetOccupancyService`、`ReviewFlowDecider` | 预算不足的单据进 NEED_REVIEW 且 `review_findings` 含预算项 |
| R1-7 | 前端：报销单详情/预算页展示"本单占用 / 部门已用 / 剩余" | `reimbursement/detail.vue` | 手动核对数值与 DB 一致 |

> 未配置预算的部门：**不阻断**（保持现状语义，仅告警）——存量种子只覆盖 4 部门 1 周期。

### R2 · 发票标识符入链（B-3，查重与交叉核验的前置）

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R2-1 | `OcrExtractTool.normalize` 补 `invoiceCode`/`invoiceNum`（数据已有，只是被丢弃） | `OcrExtractTool.java` | 单测：VAT 样例输出含发票号 |
| R2-2 | 新增 `invoice_record` 投影表（绕开 MySQL 5.7 JSON 检索限制）+ 迁移 | `migration-P3.8.sql`、`tables.md` | `uk(tenant_id, invoice_code, invoice_num)` 生效 |
| R2-3 | OCR 回写链路同步投影（`AttachmentService.updateOcrResult` 内收敛） | `AttachmentService.java`、新增 `InvoiceRecordService` | 单测：回写 OCR 后 `invoice_record` 落行 |
| R2-4 | 历史数据回填脚本（从 `expense_attachment.ocr_result` JSON 抽票号） | 迁移脚本 | 回填报告输出命中数 |

### R3 · 按票查重 + 票据-明细交叉核验（B-4 / B-5）

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R3-1 | 重写 `queryDuplicates`：一级按 `(invoice_code, invoice_num)` 硬命中（HIGH），二级保留金额+日期+商户（MEDIUM） | `ReimbursementService.java` | 单测：同票重复入账 → HIGH；同额同商户不同票 → MEDIUM |
| R3-2 | `DuplicateCheckVO` 增 `dupLevel`；`ReviewFlowDecider` **仅 HIGH 触发** `RISK_HIT`，MEDIUM 只展示 | `DuplicateCheckVO.java`、`ReviewFlowDecider.java` | 单测：MEDIUM 不产生 reviewReason |
| R3-3 | 新增 `invoice_match` 工具（票据金额/日期/商户 vs 明细行，容差 0.01） | 新增 `InvoiceMatchTool.java`、`ToolCode` 枚举、`tool_registry` 种子 | 单测：100 元票 + 800 元明细 → 不一致 |
| R3-4 | `RuleBasedFlowEngine` 在 `rule_check` 后插入 `invoice_match` 步骤 | `RuleBasedFlowEngine.java` | `RuleBasedFlowEngineTest` 断言步骤序列 |
| R3-5 | 离线规则验真（已决策：先做免费方案）：发票代码位数规则、税号格式、开票日期与票号区间合理性 | `InvoiceMatchTool` 或独立 `InvoiceVerifyTool` | 单测：构造非法票号 → 命中 |
| R3-6 | `ReviewFlowDecider` 接入 `invoice_match` 命中 → `RULE_FAIL` | `ReviewFlowDecider.java` | 单测：不一致 → NEED_REVIEW |

### R4 · 结构化 findings 与驳回重提引导（B-7）

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R4-1 | 新增 `ReviewFinding` 结构（`code/level/itemIndex/itemName/expected/actual/gap/suggestion`） | 新增 `domain/ReviewFinding.java` | 纯数据类可单测 |
| R4-2 | `review_reasons` 从字符串升级为结构化 findings：改造 `TriggerTypeResolver` + 各判定点产出 finding | `TriggerTypeResolver.java`、`ReviewFlowDecider.java`、`RuleBasedFlowEngine.java` | 单测：规则超标 → finding 含 expected/actual/gap |
| R4-3 | `audit_ticket` 增 `review_findings JSON` 列（`risk_desc` 字符串摘要兼容保留）+ 迁移 | `migration-P3.8.sql`、`AuditTicket.java`、`AuditTicketVO.java` | 工单详情返回结构化 findings |
| R4-4 | 前端"待修正项清单"：报销单详情/工单详情展示定位到明细行的期望值/实际值/差额/建议 | `reimbursement/detail.vue`、`audit/detail.vue` | 手动：超标单据显示"住宿费 实际680 标准500 超180 建议调整" |
| R4-5 | `edit.vue` 明细行标注问题项 + 预填建议值 | `reimbursement/edit.vue` | 手动：进入修改页对应行标红并预填 |
| R4-6 | 提交幂等（B-9）：`submit` 增幂等键（前端 UUID + `uk(tenant_id, idem_key)`） | `ReimbursementSubmitRequest`、`ExpenseReimbursement`、迁移 | 单测：同幂等键重复提交 → 返回原单不新建 |

### R5 · Agent 语义自校验与自主纠错（A-1，放大器；依赖 R2/R3 数据）

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R5-1 | 新增 `SelfConsistencyChecker`（5 条断言，第 ③④ 条来自 R3 的真实产出） | 新增 `domain/SelfCheckResult.java`、`service/SelfConsistencyChecker.java` | 单测：5 条断言各自命中/通过 |
| R5-2 | `RuleBasedFlowEngine` 插入 `self_check` 步骤（LLM 风控后、汇总前） | `RuleBasedFlowEngine.java` | `RuleBasedFlowEngineTest` 断言步骤序列与顺序 |
| R5-3 | 矛盾命中 → 重跑风控步骤（增强 prompt 携带矛盾提示），上限 1 次；超限 → `NEED_REVIEW` + findings | `AgentOrchestrator.java` | 单测：构造矛盾 → 重跑 1 次 → 仍矛盾则 NEED_REVIEW |
| R5-4 | `agent_task` 增 `correction_count`、`self_check_result` 列 + 迁移 + VO 透出 | `migration-P3.8.sql`、`AgentTask.java`、`TaskVO.java` | 前端任务详情可见"纠错 N 次 + 自校验明细" |
| R5-5 | **AUTO_PASS 基线实测**：构造 20~30 张小样本单据，统计自动通过率与各 reviewReason 分布，据此决定是否放宽 `confidence<0.7`（决策 5） | 测试脚本 + 结果记录 | 输出基线报告，作为阈值调整依据 |

### R6 · 结构与契约（A-2 / A-4 / A-5、P1-1 / P1-4 / P1-5 / P1-6）

| 序 | 动作 |
|---|---|
| R6-1 | `GENERIC` 产品化：前端"智能分析"页 + 接入自校验 + 高风险建审批工单 |
| R6-2 | `ToolAccessGuard` 租户基准改为网关/JWT 派生租户（与"声明租户"分离传入），使校验真正可触发 |
| R6-3 | 工具缓存 Key 加租户前缀 `tool:exec:{tenantId}:{code}:{sha256}` + 读写 try/catch 降级 |
| R6-4 | `tool_registry` 增 `output_schema` 列 + 注册时校验 Schema 合法性/强度；修正 `budget_query` 的 `deptName` 必填（改 `deptId` 或二者任一） |
| R6-5 | `RuleBasedFlowEngine` 声明式化（`FlowDefinition` 抽离，Java 默认定义） |
| R6-6 | 多 Agent 表述澄清：`AgentRole` 注释 + `task-orchestration.md` 明确"进程内角色化，非跨服务 A2A"（**诚实表述优先于夸大**） |
| R6-7 | 补 `common-jwt-starter`/`common-mq-starter` README；建 `docs/architecture/conventions.md` + README 规范入口 |

### R7 · 一致性与文档收口（P1-7 ~ P1-14、全部 D 类）

| 序 | 动作 |
|---|---|
| R7-1 | 清理环境变量回退默认值（Nacos/RabbitMQ/MinIO），缺失即启动失败；统一 `.env.example` 与 yml 口径 |
| R7-2 | 角色绑定校验 roleId 归属；批量 ID 去重（或 `INSERT IGNORE` 语义） |
| R7-3 | Feign 全局超时配置（connect/read） |
| R7-4 | `AgentTaskService.requireVisible` null 修复 + 形参 `finance` → `viewAll` 改名 |
| R7-5 | 部门写操作加 `@Transactional`；返回 `DeptVO`；`SysDept` 补 `from/apply` |
| R7-6 | 上传大小校验（后端 `FileService` + 前端 `before-upload`），对齐文案 20MB |
| R7-7 | 删除 `validateAllOwned` 死代码；file-service 删除接口本轮只登记 TODO |
| R7-8 | MQ publisher confirm + DLQ 消费告警（日志级）+ `RetryInterceptor`（限次 + 退避） |
| R7-9 | D-1 ~ D-16 全量文档同步（**与对应代码同 commit**），含 `tool-service.md` 三处形状级错误 |
| R7-10 | 清理 `rag-service/target` 残留；`docs/deploy` 补增量迁移执行说明；`docs/test` 补评估用例模板 |

### R8 · 可选增强

| 序 | 动作 |
|---|---|
| R8-1 | 离线规则验真升级为外部查验服务商对接（**待商务决策**，见 §4.3 决策 2） |
| R8-2 | 主动通知：站内信 / Webhook（B-8） |
| R8-3 | 进度百分比与预计等待时间（进度查询体验） |
| R8-4 | `reimbursement/list.vue` 补轮询 |

### R9 · P4 前置（只做数据源，不建大盘）

| 序 | 动作 |
|---|---|
| R9-1 | Token 用量落库（`model_call_log` 表或 `agent_task_step` 扩展列）+ 结构化日志；`usageSnapshot()` 接出口 |
| R9-2 | 任务/步骤耗时落库（补 `duration_ms`） |
| R9-3 | 指标口径文档（效率/风控/成本三类，`ProjectRequirements §七` → 表字段映射）；新增财务专属指标：票据识别准确率、重复报销拦截率、预算超支预警次数、**自动通过率** |

---

## 6. 风险与预案

| 风险 | 预案 |
|---|---|
| 风险 | 预案 |
|---|---|
| R0-3（file-service 内外链路）改动影响附件展示主流程 | 先补集成测试复现"附件静默空白"，再改；改动保留旧行为开关 |
| R0-6/R0-7 权限收口导致前端直连调用被打回 | 收口前 grep 前端所有 `/audit/**`、`/tools` 调用点；内部调用改走 Feign 服务名直连（不经网关） |
| R0-8 改 `update-strategy` 影响面大（所有实体 null 字段语义） | **已决策**：用 `LambdaUpdateWrapper` 局部修，不全局改策略 |
| **R1 预算占用并发超支**（核心风险） | 占用走**单条原子 SQL + 超支条件**，不读不算不写；补 20 线程并发单测；`budget_occupancy` 记账表保幂等 |
| R1 接入点遗漏导致占用/释放不配对（预算被永久吃掉） | 枚举全部终态路径逐一登记（approve / withdraw-agree / terminate / reject / onRerunFail）；加"占用-释放配平"对账：`SUM(OCCUPIED) - SUM(RELEASED)` 应等于 `budget.used_amount` |
| **R2 新增 `invoice_record` 投影表**与服务收敛规范冲突 | 新增 `InvoiceRecordService` 作为唯一写入口；OCR 回写链路经它投影，禁止外部持有 Mapper（AGENTS.md §5.9） |
| R2 历史回填出现脏票号（空号/重复） | 回填脚本输出清单人工核对；`uk(tenant_id, invoice_code, invoice_num)` 兜底；空票号不入投影表 |
| R3-1 改查重判据可能放宽真实风控 | MEDIUM 降级为"仅展示"是**产品决策**（缓解自动化率），验收时用历史单据回归，确认 HIGH 判据覆盖率不降 |
| R3-3 新增工具使流水线步骤数变化，前端/测试硬编码步数预期 | 检查 `RuleBasedFlowEngineTest` 与前端是否假设固定步数；改为"按 `stepNo` 顺序渲染" |
| R5-3 重跑风控步骤导致任务耗时与 Token 消耗上升 | 上限 1 次 + 仅命中矛盾时触发；R5-5 基线报告量化影响 |
| R6-1 `GENERIC` 产品化后 LLM 规划质量不可控（历史上曾出现步骤乱/顺序漂移） | 复用 `sanitize`（剔除目录外工具）+ 自校验；保留 fallback 模板 |
| 双远程推送冲突（历史曾出现 gitee 直接提交需合并） | 推送前 `git fetch --all` 对比 `gitee/main` 与 `github/main` 差异，先对齐再推 |
| 文档与代码再次漂移 | R7-9 强制"同 commit"；`hardening-checklist.md` 作为 PR 检查项 |
| **Maven 构建无法在本机验证**（走查期 shell 被沙箱拒绝） | 每轮以"能编译 + 单测通过"为提交前提；若本机不可用，先只提交可静态审查的改动，构建验证延后并显式标注 |

---

## 7. 验收清单（本阶段完成定义）

### 业务闭环（R1~R4，优先级最高）

1. **预算真实占用**：approve 后 `used_amount` 增加；同一部门预算 10 万、两笔 6 万并发提交审批 → **第二笔被拦**（并发测试）；未配置预算的部门不阻断仅告警
2. **预算释放配平**：approve → withdraw-agree 后 `used_amount` 回到原值且不为负；对账 `SUM(OCCUPIED) - SUM(RELEASED) == used_amount`；重复 approve 幂等
3. **发票号入链**：`ocr_result` 含 `invoiceCode`/`invoiceNum`；`invoice_record` 落行且唯一键生效；历史数据回填完成并输出清单
4. **按票精准查重**：同一张票重复报销 → `dupLevel=HIGH` 且触发人工；同额同商户不同票 → `MEDIUM` **不触发人工**；**同一发票拆进两张不同金额单据能查出**（旧算法查不出）
5. **票据-明细交叉核验**：上传 100 元发票、明细写 800 元 → 进 NEED_REVIEW 并给出不一致 finding（旧行为：静默通过）
6. **离线规则验真**：构造非法发票代码/税号格式 → 命中，且与联网查验在结论中区分标识
7. **驳回重提引导**：超标单据工单详情显示"住宿费 实际 680 / 标准 500 / 超 180 / 建议调整"；修改页对应明细行标红并预填建议值
8. **提交幂等**：同幂等键重复提交只产生一条单据

### Agent 能力（R5~R6）

9. **自主纠错可演示**：构造 LLM 汇总结论与前序核验结果矛盾的场景 → 自校验命中 → 风控步骤自动重跑 1 次 → 仍矛盾则进审批工单且原因含结构化 findings；任务详情展示"纠错 1 次 + 自校验明细"
10. **AUTO_PASS 基线**：输出 20~30 张小样本的自动通过率与 reviewReason 分布报告，作为阈值调整依据
11. **GENERIC 产品化**：前端"智能分析"入口可提交自然语言任务，看到 LLM 自主拆解的步骤与结果
12. **工具契约对称**：`budget_query` 传 `deptId` 不再被 Schema 拦；`output_schema` 存在时出参被校验；`ToolAccessGuard` 租户校验可被真实触发

### 主链路止血与质量（R0、R7~R9）

13. **主链路止血**：OCR 挂起 10s 内失败而非无限阻塞；工具执行失败路径必回 `tool.result`；同租户 A 看 B 的报销单附件可见且直连猜 id 仍 403
14. **前端两处破坏性缺陷修复**：改用户手机号不清空角色；移除权限后菜单/按钮即时收敛
15. **权限收口**：普通用户调 `/api/v1/audit/**` 写端点与 `/api/v1/tools` 列表 → 403；无租户头直连一律拒绝
16. **部门解绑可用**：传 `deptId=0` 后 `sys_user.dept_id` 为 null
17. **加固清单可执行**：`hardening-checklist.md` 五类逐项可勾选，R7 全部销项
18. **文档零形状级错误**：`tool-service.md` 三个工具结果形状与代码一致；`tenant-service.md` 补全 3 个端点与 `perms/deptId`；`file-service.md` 记录归属校验规则
19. **规范可公开**：`conventions.md` 落地 + README 规范入口；两个缺失 Starter README 补齐
20. **构建通过**：`mvn clean install` 全绿，前端 `npm run build`（含 `vue-tsc -b`）全绿
21. **双远程一致**：`gitee/main` 与 `github/main` 内容与历史一致

---

## 8. 分支与提交策略

- **分支**：`refactor/agent-autonomy-hardening`（已创建，基于 main `8eca9f8`）
  > 分支名侧重 Agent，但实际范围已扩展为"业务闭环 + Agent 补强"。若评审认为需更准确可另开分支；当前不动，避免重复建分支。
- **提交粒度**：按 R0-1 ~ R9-3 的**单条动作为一个 commit**，前缀遵循 AGENTS.md §6；文档与代码同 commit
- **里程碑**：
  1. R0 完成后单独评审（止血优先，可独立合并）
  2. R1 完成后单独评审（**预算占用是地基，含 DDL 与并发逻辑，重点评审**）
  3. R2~R4 合并评审（业务闭环主体）
  4. R5~R6 评审（Agent 能力，重点看自校验断言是否落在真实数据上）
  5. R7~R9 合并评审（一致性收口与 P4 数据源）
- **推送**：`git push gitee <branch>` + `git push github <branch>`，PR 标题 `refactor: 业务闭环与 Agent 能力补强——预算真实占用/按票查重/票据明细交叉核验/驳回引导/语义自校验纠错/主链路止血`
- **⚠️ git 操作纪律（AGENTS.md §6 硬性约束）**：`commit` / `push` / `merge` / `rebase` / `reset` **仅在用户明确指令后执行**；阶段号、计划名、"按你的来"、"这一步做完了"等表述**均不构成授权**。改动默认只留在工作区

---

## 9. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| `docs/planning/P3.5-execution-plan.md` | 本阶段修其 §5 R3 未勾选问题（实际已完成） |
| `docs/planning/future-roadmap.md` | 新增 `P3.8` 行；销项：`FAILED 重跑`（部分）、`工具治理增强`（补出参）、`安全加固深化`（部分）、`幻觉拦截`前半段 |
| `docs/planning/P3-execution-plan.md` §8 | 销项：`幻觉拦截 → 正式评估体系`的**前半段（检测与强制人工）在本阶段落地**，评估大盘仍留 P4 |
| `docs/ProjectRequirements.md` §七 | 新增财务专属指标落地：票据识别准确率、重复报销拦截率、**预算超支预警次数**、自动通过率 |
| `docs/ProjectBusiness.md` §二 | 补齐其业务流程中缺失的两环：**预算占用累加（原注释标"待实现"）** 与 **票据-明细一致性核验** |
| 需求标准③ | 靶点：补上"任务结果自校验"与"工具调用容错增强" |
| 需求标准② | 销项：MQ 重试拦截器；网关限流与日志分层仍留 P5 |
| 需求标准⑤ | 本阶段只铺数据源（R9），大盘仍留 P4 |

---

## 10. 本阶段新增/变更的数据库对象（汇总）

| 表 / 列 | 类型 | 归属 | 说明 |
|---|---|---|---|
| `budget_occupancy` | **新增** | R1 | 占用记账：`uk(tenant_id, reimb_id)` 保幂等；`status` OCCUPIED/RELEASED；支撑占用-释放配平对账 |
| `budget.used_amount` | 语义变更 | R1 | 从"只读种子值"变为**真实累加** |
| `invoice_record` | **新增** | R2 | 发票号投影表（绕开 MySQL 5.7 JSON 检索限制）；`uk(tenant_id, invoice_code, invoice_num)` |
| `audit_ticket.review_findings` | 新增列 | R4 | 结构化复核明细（`risk_desc` 字符串摘要兼容保留） |
| `expense_reimbursement.idem_key` | 新增列 | R4 | 提交幂等；`uk(tenant_id, idem_key)` |
| `agent_task.correction_count` | 新增列 | R5 | 自校验纠错次数（P4 指标来源） |
| `agent_task.self_check_result` | 新增列 | R5 | 每次自校验的断言详情 |
| `tool_registry.output_schema` | 新增列 | R6 | 工具出参契约（与 `input_schema` 对称） |

> 全部 DDL 收敛到一个迁移脚本 `docs/database/migration-P3.8.sql`（幂等、`information_schema` 存在性判断包裹 ALTER），并同步 `finaudit-schema.sql` 与 `docs/database/tables.md`。

---

## 11. 执行进度

### R0 · 主链路止血 —— ✅ 代码已改完（**待构建验证与提交**）

> ⚠️ **执行环境限制**：本次实施期间 shell 被沙箱拒绝（`SetNamedSecurityInfoW failed (Win32 5): grantWrite(...)`，
> 尝试 7 次 / 4 种调用方式均失败），因此 **`mvn clean install`、单测、`git commit/push` 全部无法执行**。
> 以下改动仅经静态审查（逐文件通读 import/签名/路径一致性），**未经编译验证**。首次可构建时请优先跑：
> `mvn -q clean install` + 前端 `npm run build`，再执行提交推送。

| 序 | 状态 | 落点 | 要点 |
|---|---|---|---|
| R0-1 | ✅ | `BaiduOcrService`、`common-ocr-starter/README.md` | `baidu.timeout-ms` 接入 `SimpleClientHttpRequestFactory`（connect+read），补非正数回退 10s；死配置变为真实生效。同款做法对齐 `OcrExtractTool` |
| R0-2 | ✅ | `ToolExecutionService`、`ToolExecutionLog` | ① 新增 `saveLogQuietly`：日志落库任何异常只 WARN，**保证任何路径都回吐 `tool.result`**（原先 catch 分支内落库失败会吞掉结果且不进 DLQ，步骤永久 RUNNING）② `input_params` null 归一化为空 Map，避免 `JSON NOT NULL` 被 NOT_NULL 策略跳过而插入失败 |
| R0-3 | ✅ | `FileService`、`FileController`、**新增 `InternalFileController`**、`FileServiceFeign`、`file-service.md` | 拆出 `/internal/files/**`（仅租户隔离）供业务服务代读；`requireReadable` 语义收敛为「用户可见性」，无上下文改为**拒绝**（原为放行）；对外移除批量端点 |
| R0-6 | ✅ | **新增 `InternalAuditDataController`**、`AuditDataController`（已停用待删）、`AgentCoreServiceFeign`、网关 yml、`gateway.md`、`agent-core.md` | 6 个工具-facing 端点迁 `/internal/audit/**`；网关路由由 `Path=/api/v1/audit/**` **收窄为 `/api/v1/audit/tickets/**`**（OCR 回写等写操作不再暴露给登录用户） |
| R0-7 | ✅ | `ToolController`、**新增 `InternalToolController`**、`ToolServiceFeign` | `list` 挂 `tool:manage`；内部工具目录拆到 `/internal/tools`（对外挂权限码后内部 Feign 会被 fail-closed 403，故必须拆分而非开后门） |
| R0-4 | ✅ | `system/user.vue` | `openEdit` 改 async，调 `getUserDetail` 回填 `roleIds`（该函数此前全项目 0 调用）；取详情失败不打开弹窗，防误清空角色 |
| R0-5 | ✅ | `api/request.ts`、**新增 `api/authRaw.ts`** | 403 刷新改用绕过拦截器的裸请求（原实现取 `resp.data.code` 恒 undefined → 死代码）。独立成 `authRaw.ts` 以避免 `request.ts ↔ auth.ts` 循环依赖 |
| R0-8 | ✅ | `SysUserService` | `deptId=0` 解绑改用 `LambdaUpdateWrapper` 显式 `SET NULL`（原 `apply` 转 null 后被 NOT_NULL 策略跳过，解绑不生效） |
| R0-9 | ✅ | `ToolController`、`FileController`、`InternalAuditDataController`、`InternalFileController` | 拆除全部 `X-Tenant-Id` 的 `defaultValue="1"`，改 fail-closed（缺失即拒） |

**实施中发现并一并修正的额外问题**（原症状表未列）：

1. **文档幽灵端点**：`docs/api/agent-core.md` 记录了 `GET /api/v1/audit/budgets/dept-exists`，但代码中该端点**不存在**（P3.5b 已由 `/budgets/allowed` 取代）——文档漂移第 17 项。
2. **契约复用冲突**：`GET /api/v1/tools` 与 `GET /api/v1/files{?ids=}` 同时是「对外端点」与「内部 Feign 契约」，**无法直接挂权限码**（挂则内部链路 403）。这是 R0-6/R0-7 必须拆 `/internal` 而非单纯加注解的根本原因，已在计划中补记。
3. **测试影响**：现存单测均为 Mockito 纯单元测试（无 `@SpringBootTest`/`MockMvc`），只 mock Feign 接口，**路径变更不影响其编译与运行**（已 grep 确认）。

### 待办（下一步）

- [x] ~~首次可构建环境验证 R0~~ **已完成**：`mvn clean install`（JDK 21）19 模块 BUILD SUCCESS；`mvn test` 全绿（tenant 20 / agent-core 66 / tool 11 / file 3 / starter 若干，0 失败 0 错误）
- [x] ~~`git rm` 停用的 `AuditDataController.java`~~ **已删除**
- [ ] 按 AGENTS.md §6 提交 + 双仓推送（R0 为一个阶段）
- [ ] 进入 R1（预算真实占用与释放）

### R0-10（新增，联调期发现的阻断级故障）：Redis 连接工厂与 spring-data-redis 3.5.0 不兼容

> 现象：服务启动后**前端登录即失败**，tenant-service 抛
> `ServletException: Handler dispatch failed: java.lang.StackOverflowError`，
> 栈顶是 `DefaultedRedisConnection.pExpire`（数千帧自我递归）。

**根因（已用字节码逐层证实）**

1. 本工程 `spring-data-redis` 为 **3.5.0**。该版本给 `RedisKeyCommands` 新增了抽象方法
   `pExpire(byte[], long, ExpirationOptions.Condition)`（`pExpireAt` 同理）。
2. `DefaultedRedisConnection.pExpire(byte[], long)` 的实现是
   `keyCommands().pExpire(key, ttl, Condition.ALWAYS)`，而 `keyCommands()` 返回 `this`。
   ⇒ **若具体连接类没实现那个三参抽象方法，调用会回到自己**，形成无限递归。
3. 具体连接类是 `org.redisson.spring.data.connection.RedissonConnection`（来自
   `redisson-spring-boot-starter` 传递的 **`redisson-spring-data-34`**，面向 spring-data-redis **3.4**）。
   `javap` 证实它**只声明了** `pExpire(byte[], long)`，**没有**三参重载。
4. `redisson-spring-data-35`（适配 3.5 的版本）在本地仓库只有 `.pom.lastUpdated` 空壳，且本机 Maven Central 不可达 → 换适配包此路不通。
5. 而 Lettuce 侧的 `LettuceKeyCommands` **已实现** 3.5.0 新 API（字节码中引用 `ExpirationOptions` 证实）。
6. `RedissonAutoConfiguration` 带 `@ConditionalOnMissingBean(RedisConnectionFactory.class)`，
   会注册 `RedissonConnectionFactory` 顶掉 Boot 默认的 Lettuce 连接工厂 —— 故障因此在**任何设置 TTL 的 Redis 操作**上必现（登录写权限快照就是第一处）。

**修复**

| 变更 | 内容 |
|---|---|
| `backend/pom.xml` | dependencyManagement 由 `redisson-spring-boot-starter` 改为 **`org.redisson:redisson`**（核心包，3.47.0） |
| `common-redis-starter/pom.xml` | 依赖改为只引 `redisson` 核心包，**不引 starter**（连带 `redisson-spring-data-34` 一并消失） |
| `CommonRedisAutoConfiguration` | 删除 `RedissonAutoConfigurationCustomizer`（随 starter 移除）；新增显式 `redissonClient(RedisProperties)` Bean（单机、空密码归一化、超时/库号映射），**不注册 RedisConnectionFactory**；`DistributedLockTemplate` 不变 |

结果：连接工厂交回 Boot 默认 Lettuce（兼容 3.5.0）；Redisson 仅提供 `RedissonClient` 用于分布式锁。
依赖树已验证：`spring-data-redis 3.5.0 + lettuce 6.5.5 + redisson 3.47.0`，**`redisson-spring-data-34` 已不在树中**。

**经验沉淀**：升级/混用 `spring-data-redis` 与 Redisson 时，必须核对 `redisson-spring-data-3x` 适配包版本与 spring-data-redis 主版本一致；
不一致的典型症状不是启动失败，而是**运行期在 `DefaultedRedisConnection` 的默认方法上无限递归**。

### R0-11（新增，联调期发现的 dev 体验问题）：Element Plus 按需引入导致首次访问页面整页刷新

> 现象：前端（`npm run dev`）**头几次点菜单会整页刷新抖动**，访问若干页面后变正常；生产构建不受影响。

**根因（已用真实 dev 服务实测复现并定位）**

1. Element Plus 组件由 `unplugin-vue-components` **按需**引入，其组件与样式导入是**虚拟模块**，
   Vite 启动期的依赖扫描器扫不到；路由又全部是动态 `import()`（15 条），依赖只能在**首次访问某页时**才被发现。
2. 未预先声明的后果，dev 控制台原样输出：
   ```
   [vite] ✨ new dependencies optimized: element-plus/es/components/timeline/style/css, ... （11 个）
   [vite] ✨ optimized dependencies changed. reloading        ← 浏览器整页刷新
   [vite] ✨ new dependencies optimized: ...（22 个）
   [vite] ✨ optimized dependencies changed. reloading        ← 再刷一次
   ```
3. 实测确认：`optimizeDeps.include` 里写 `element-plus/es/components/*/style/css` 这类**通配无效**
   （Vite 6.4.3 不按 element-plus 的 exports 展开）；而**手写组件清单会漏**
   （第一版手写清单漏了 `button`，导致登录页仍抖一次）——故最终改为**自动推导**。

**修复**（只动 `frontend/vite.config.ts`，仅影响 dev 预构建）

扫描 `src/**/*.vue` 的 `<el-xxx>` 标签 + `import { ElMessage } from 'element-plus'` 函数式组件，
映射为 `element-plus/es/components/<dir>/style/css`，并以**文件系统存在性校验**兜底；
另显式补 `base`（基础样式）与 `loading`（`v-loading` 指令无标签）两个非标签样式。
推导结果启动时打印：**共 55 个依赖，其中 45 个组件样式**。

**验证（实测）**

| 项 | 结果 |
|---|---|
| `vite --force` 冷启动后遍历全部 14 个页面 | deps 文件数 **148 不变**、`_metadata.json` 时间**不变**、日志**零** `new dependencies optimized` / `reloading` → **整页刷新消除** |
| `npm run build`（含 `vue-tsc -b` 类型检查） | ✅ 通过，11.26s，主 chunk 191.25 kB（按需引入收益保持） |
| 对比修复前 | 同一操作序列触发 **2 次** `optimized dependencies changed. reloading` |

> 该问题生产构建**不存在**（打包时一次性解析全部依赖），纯 dev 体验问题。

### R0-12（新增，联调期发现的阻断级故障）：直接访问受保护路由「不跳登录」，实为路由初始化崩溃

> 现象：新浏览器直接访问 `/tasks` 等受保护路径，**没有跳到登录页**（停在原地/白屏）。

**根因（用户浏览器控制台实测栈）**

```
Error: [🍍]: "getActivePinia()" was called but there was no active Pinia.
    at useStore (pinia.js:1489:1)
    at router/index.ts 的 beforeEach（useAuthStore()）
[Vue Router warn]: Unexpected error when starting the router: ...
[Vue warn]: injection "Symbol(router view location)" not found.
Uncaught TypeError: Cannot read properties of undefined (reading 'value')   (vue-router.mjs)
```

`app.use(router)` 会**立即触发首次导航**，而 `router.beforeEach` 第一行就 `useAuthStore()`。
`pinia.install()` 内部虽然会 `setActivePinia(pinia)`，但只要初始导航在该赋值**之前/竞态**执行
（用户环境叠加了浏览器扩展注入 + HMR WebSocket 反复重连），守卫即抛错 → **路由初始化失败** →
既不渲染也不跳登录。**所以问题根本不在跳转逻辑**，而在应用没起来。

**修复（分两步，最终形态已彻底不依赖 Pinia 时序）**

第一步（消除 `activePinia` 竞态）：

| 文件 | 变更 |
|---|---|
| `src/stores/pinia.ts` | **新增**：导出单例 `pinia`（避免 `router → stores/auth` 循环，供 main 与 router 共用） |
| `src/main.ts` | `setActivePinia(pinia)` 提到 `app.use(router)` **之前**（Pinia 官方对「setup 之外用 store」的推荐做法） |

第二步（**结构上不再可能触发**——用户 Edge 环境装有会注入 Vue 的浏览器扩展，能扰乱模块时序与注入上下文，
故不再依赖任何全局/注入态）：

| 文件 | 变更 |
|---|---|
| `src/router/index.ts` | 守卫**完全移除 `useAuthStore`**，改为只读 `localStorage`（token 本就持久化在那里，是唯一真相）；`meta.perm` 也改读本地持久化 user 对象。顺带消除 `router → stores/auth → api/request → router` 循环依赖；并新增本地 JWT `exp` 预判，过期即视作未登录（不再"先闪一下受保护页再被 401 弹回"） |
| `src/api/request.ts` | 拦截器不再裸调 `useAuthStore()`（拦截器可能在挂载前或时序异常时执行）→ 新增 `currentToken()` / `clearAuthSafely()`，显式传 pinia 实例并在极端情况回退 localStorage，`useStore` 绝不抛出 |

**验证（CDP 驱动真实浏览器，全新 profile，零异常）**

| 场景 | 落点 | 异常 |
|---|---|---|
| A 无 token → `/tasks` | `/login?redirect=/tasks` | 无 |
| B 过期 token → `/tasks` | `/login?redirect=/tasks` | 无 |
| C 无 token → `/system/users` | `/login?redirect=/system/users` | 无 |
| D 无 token → `/rules`（需权限） | `/login?redirect=/rules` | 无 |
| E 无 token → `/login` | 停在 `/login` | 无 |
| F 无 token → 未知路径 `/nope` | `/login?redirect=/dashboard` | 无 |

`npm run build`（含 `vue-tsc -b`）通过。守卫中已无任何 `useAuthStore` / `pinia` 调用（仅注释提及）。

> 注：用户 Edge 首次反馈仍见旧栈（`at index.ts:106:16` + `runWithContext`），是**浏览器仍在跑改动前的 bundle**；
> 但既然扩展注入确能触发该竞态，故按上表做成了结构性免疫，而非仅修时序。

**顺带修复**（同一批 dev 体验问题）

- `vite.config.ts` 增加 `define`：`__VUE_OPTIONS_API__/__VUE_PROD_DEVTOOLS__/__VUE_PROD_HYDRATION_MISMATCH_DETAILS__`
  → 消除控制台 `Feature flags are not explicitly defined` 告警，且主 chunk **191.25 kB → 186.93 kB**。
- **已知遗留（环境侧，非代码）**：用户控制台出现
  `WebSocket connection to 'ws://localhost:5173/?token=...' failed` → HMR 连不上（本地服务端可正常升级）。
  表现为改代码不热更、必要时整页刷新；疑似本机代理/VPN/安全软件拦截 WebSocket，需在用户环境排查。

**经验沉淀**：`app.use(router)` 会立即导航，**任何在 `beforeEach` 里取 store 的项目都必须先
`setActivePinia` 或把实例显式传入 `useStore(pinia)`**；否则该错误只在特定时序（扩展注入、
WebSocket 重连、慢机器）下偶发，表现为「白屏 + 不跳登录」而非明确报错，极易被误判为守卫逻辑写错。
