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
| D-13 | `common-ocr-starter/README.md:27,41` | ~~把死配置 `baidu.timeout-ms` 当作生效配置~~ → **本登记项已随 R0-1 解决**（`3114b60` 已同 commit 修好代码与 README）。R7 复核仅剩两处措辞不精确（"真实生效"→ 标注为 R0-1 接线、"P3.8 修正"→ 修正编号），已补；并补记「token 获取与识别共用同一 RestClient，故同样受该超时约束」 |
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

### R2 · 发票标识符入链（B-3，查重与交叉核验的前置）—— ✅ 后端完成，见 [§11 R2](#r2--发票标识符入链--后端完成待用户执行迁移--联调)

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R2-1 | `OcrExtractTool.normalize` 补 `invoiceCode`/`invoiceNum`（数据已有，只是被丢弃） | `OcrExtractTool.java` | 单测：VAT 样例输出含发票号 ✅ 9/9 |
| R2-2 | 新增 `invoice_record` 投影表（绕开 MySQL 5.7 JSON 检索限制）+ 迁移 | `migration-P3.8.sql`、`tables.md` | `uk(tenant_id, invoice_code, invoice_num)` 生效 ✅ 临时库实测拦截重复 |
| R2-3 | OCR 回写链路同步投影（`AttachmentService.updateOcrResult` 内收敛） | `AttachmentService.java`、新增 `InvoiceRecordService` | 单测：回写 OCR 后 `invoice_record` 落行 ✅ 13/13 |
| R2-4 | 历史数据回填脚本（从 `expense_attachment.ocr_result` JSON 抽票号） | 迁移脚本 | 回填报告输出命中数 ✅ 临时库实测（中文存量日期亦正确解析） |

### R3 · 按票查重 + 票据-明细交叉核验（B-4 / B-5）—— ✅ 后端完成（**待用户重启联调**）

> B-4：`duplicate_check` 只有「同申请人 + 金额完全相等 + 日期±30天」再叠加商户近似，
> 命中即判疑似重复 → 正常单据被误判（R1 联调实测复现：100 元小额单因库里存在同额历史单而误报）。
> B-5：`amount_verify` 只校验「明细合计 == 申报总额」（同一份数据内部自洽）、
> `rule_check` 只校验限额标准，**两者都没有把「票面」与「明细」对上** ——
> 「明细写 800 元、实际票据只有 100 元」这类虚报走不到任何检查。

**实现要点**

| 序 | 落点 | 要点 |
|---|---|---|
| R3-1 | `ReimbursementService.queryDuplicates` 重写为两级判定 | 一级 `<b>发票号硬命中</b>`：本单任一发票的 `(invoice_code, invoice_num)` 已存在于**其他**报销单（数据源 `invoice_record`，走唯一索引）；二级：金额+商户+日期±30天近似。一级命中不再重复计入二级 |
| R3-2 | `DuplicateItemVO` 增 `dupLevel`/`invoiceCode`/`invoiceNum`；`DuplicateCheckVO` 增汇总等级 + `hasHighLevelHit()`；`DuplicateCheckTool` 输出 `dupLevel`+`suspectedHigh`；`ReviewFlowDecider` **仅 HIGH 触发 RISK_HIT** | 中置信只作展示、不阻断——这是 B-4 误报的根治。老输出无 `suspectedHigh` 字段时回落看 `suspected`，行为不突变 |
| R3-3 | 新增 `invoice_match` 工具 + `InvoiceRecordService.matchInvoices` + 内部端点 `/internal/audit/reimbursements/{id}/invoice-match` | 三项交叉核验：有明细无发票（`NO_INVOICE`）、申报合计超票面合计（`AMOUNT_MISMATCH`）、单笔明细超票面合计（`ITEM_EXCEEDS_INVOICE`）；容差 0.01 |
| R3-4 | `RuleBasedFlowEngine` 在 `rule_check` 之后插入 `invoice_match` 步骤（**仅当有附件**） | 紧跟规则校验：此时限额已判完，正好用票面数据校验明细是否虚报 |
| R3-5 | 离线规则验真（已决策：先做免费方案，不引入付费查真接口） | 发票代码位数（10/12/20，缺失合法——2018 年起电子发票代码并入号码）、代码纯数字、开票日期不得晚于当前日期、不得早于当前 10 年 |
| R3-6 | `ReviewFlowDecider` 接入 `invoice_match` 命中 → `RULE_FAIL:票据与明细不一致（编码…）` | 进人工复核而非硬失败；原因串带异常编码便于定位 |

**设计决策**

1. **分级用 `dupLevel` 字符串（`LEVEL_HIGH`/`LEVEL_MEDIUM`）而非布尔**：布尔无法表达未来可能出现的
   第三级（如「同商户同额但日期超出容差」），且字符串前缀便于日志与前端分档展示。
2. **比对逻辑放 agent-core 而非 tool-service**：`invoice_record` 数据访问收敛在
   `InvoiceRecordService`（AGENTS.md §5.9），tool 只做入参装配与结果聚合——
   与本项目既有分工一致（规则评估在 agent-core、工具只装配）。
3. **`invoice_match` 也纳入 `ToolAccessGuard` 的 `checkReimbOwnership`**：它按 `reimbId` 取发票数据，
   与 `duplicate_check`/`ocr_extract` 同类，必须做同一道跨租户归属校验。
4. **`NO_INVOICE` 判为「不一致」而非「一致」**：有明细却无发票时若判一致，
   等于把「没数据」当成「没问题」。

**验证（AI 自测，非用户验收）**

| 项 | 方式 | 结果 |
|---|---|---|
| 交叉核验单测 | 新增 `InvoiceMatchTest` | **13/13 通过**：100 元票 + 800 元明细 → 不一致（含单笔越界）；金额相符一致；0.01 容差；申报小于票面不误报；有明细无发票 → `NO_INVOICE`；claimedTotal 缺省时按明细求和 |
| 离线验真单测 | 同上 | 代码位数异常/含非数字/Future 日期/过旧日期各自命中；**电子发票无代码不误报**；合法发票零 flags |
| 分级单测 | `ReviewFlowDeciderTest` 扩到 9 例 | **中置信不触发 RISK_HIT**（B-4 根治点）、硬命中触发、老输出兼容回落、票据不一致 → `RULE_FAIL` 且带编码、票据一致不阻断 |
| 分级单测 | `ReimbursementServiceTest` | 二级命中 `dupLevel=LEVEL_MEDIUM` 且 `hasHighLevelHit()==false` |
| 流水线单测 | `RuleBasedFlowEngineTest` | 有附件 8 步（含 `invoice_match` 且携 reimbId/items/claimedTotal）、无附件 5 步（跳过 OCR 与票据核验） |
| 防越权单测 | `ToolAccessGuardTest` 扩到 13 例 | `INVOICE_MATCH` 跨租户拒绝、本租户放行 |
| 构建 | `mvn -o clean install`（19 模块） | **BUILD SUCCESS** |
| schema | 导入一次性库 | 退出码 0，**20 张表**，`tool_registry` 含 `invoice_match`（id=6） |
| 迁移 | 已在真实 `finaudit` 库执行 | 退出码 0，`invoice_match` 注册成功；**重复执行退出码 0 且行数不变（幂等）** |

> 一个必须记住的运维点：`tool-service` 执行工具前会按 `tool_code` 查 `tool_registry`，
> **查不到即抛「工具未注册或已禁用」**，流水线到该步直接失败。故新工具必须同时
> ① 加 `ToolCode` 枚举、② 实现 `ToolExecutor`、③ 在 `tool_registry` 注册（见迁移第 6 节）。
> 本阶段漏了 ③ 会表现为「票据核验」步骤必然失败。

**⚠️ R3-7（端到端验收暴露的架构缺陷）：一票多单的归属关系存不下，硬命中恒不触发**

首轮端到端跑出 `dupLevel=NONE`（判据① 失败）。逐单核对后发现根因不是时序、也不是判定逻辑，
而是**表结构设计缺陷**：

```
同一张票提交 5 次后，invoice_record 只有 1 行：
  invoice_num=07632553  reimb_id=57（最后一次）  seen_count=14
逐单查询：当前单 53/54/55/56 各自的判定都是「命中行的 reimb_id == 当前单 → 属于自己 → 排除」
         而 reimb_id 恒为 57 —— 于是除 57 之外的所有单都把自己排除掉，硬命中永不触发
```

- **根因**：`invoice_record` 的 `uk_invoice(tenant_id, invoice_code, invoice_num, deleted)` 决定
  一张票只有一行，而该行只带**一个** `reimb_id`；「同一张票被多张报销单共用」这个事实**无处存放**，
  可按票查重的判定恰恰要回答「这张票还属于哪张单」。
- **修复**：新增 `invoice_reimb_link` 关联表承接一对多（`uk(tenant_id, invoice_record_id, reimb_id, deleted)`），
  OCR 投影时同步建立/累加归属；硬命中改为查关联表
  （`InvoiceRecordService.findOtherReimbIds(invoiceRecordId, currentReimbId)`）；
  `invoice_record.reimb_id` 降级为「最近一次归属」仅供展示。
- **迁移第 6 节**建表 + 按现有 `reimb_id` 回填历史归属（`INSERT IGNORE`）。

> 教训：**「唯一索引决定一行」与「需要记录多对多」是同一张表上的冲突**。
> 设计投影表时就该问一句「这张表要回答的关系是一对一还是一对多」——
> 我当时只想着「一张票一条记录」（对票本身成立），却把「票与单的归属」也塞进了同一行。

**首轮端到端结果（修复前）**：判据②③ 通过、判据① 失败（`dupLevel=NONE`）；
判据② 的实测输出同时证实交叉核验工作正常：
`{"match": false, "invoiceTotal": 7741.75, "claimTotal": 50000, "gap": 42258.25, "flags": [AMOUNT_MISMATCH, ITEM_EXCEEDS_INVOICE]}`。

**待办**

- [x] 迁移已在真实库执行（关联表 + `invoice_match` 注册）
- [ ] **重启 agent-core-service（关联表修复需重新构建 + 重启才生效）**
- [ ] 联调复验：① 同一张票提交两次 → `duplicate_check` 返回 `LEVEL_HIGH`；
      ② 一张金额明显小于明细的单据 → `invoice_match` 报 `AMOUNT_MISMATCH`；
      ③ 普通正常单据不因「同额历史单」被误判重复（B-4 回归）



### R4 · 结构化 findings 与驳回重提引导（B-7）—— 🔶 后端主体完成（R4-4/4-5 前端延后、R4-6 幂等未做）

> ⚠️ **R4 验收的补正（2026-09-21，R5 阶段发现）**：`r4-review-findings-e2e.ps1` 19/19 通过**未覆盖
> amend 重跑链路**。该链路的入口 `AgentTaskService.prepareRerun` 用 `wrapper.set` 写 JSON 列
> `input_params`，在真实库上必然抛 `MysqlDataTruncation`（详见 §11 R5-9），即
> **「工单驳回 → 提交人修改后重跑」此前不可用**。修复已随 R5 一并提交，需在 R5 复验时补测该链路。

> B-7：驳回只给一句 `risk_desc` 文字（如「规则校验超标」），提交人**不知道该改哪一行、改成多少**。
> 本阶段把复核原因从「字符串列表」升级为「结构化问题项」，直接支撑业务目标里的**驳回重提引导**。

**R4a（本次完成：R4-1 ~ R4-3）**

| 序 | 落点 | 要点 |
|---|---|---|
| R4-1 | 新增 `domain/ReviewFinding` | `code / level / itemIndex / itemName / expected / actual / gap / suggestion`；提供 `ofItem` / `ofDocument` / `ofDocumentAmount` 三个工厂（自动算 gap）；`toReason()` 派生兼容的原因串（带行号与差额） |
| R4-2 | `ReviewFlowDecider` 改为产出 findings；`FlowDecision` 同时承载 `findings`（权威）+ `reviewReasons`（派生） | 各判定点（rule_check / budget_query / duplicate_check / amount_verify / invoice_match / LLM）各自产出结构化问题项；`reasons` 严格由 `findings` 逐条派生，不再是独立数据源 |
| R4-2b | `RuleHitVO` 增 `itemIndex/itemName/expected/actual`；`FinanceRuleService` 四处规则评估改为输出结构化数值 | 这是关键决策：**不再从 `message` 文本反向解析金额**（那种做法极脆弱）。差旅标准的标准值取「单日标准 × 晚数」，与住宿总金额同口径才可比 |
| R4-3 | `audit_ticket` 增 `review_findings JSON` 列（迁移第 9 节，幂等）+ `AuditTicket` / `AuditTicketVO` 透出 | `review_reasons` 继续保留（字符串摘要，兼容既有消费方）；重跑复位时 findings 一并刷新 |
| R4-3b | `TriggerTypeResolver.resolveByFindings` | 结构化后直接按 `level` 解析 triggerType，无需再解析文本前缀（`resolve(List<String>)` 保留给历史数据） |

**设计决策**

1. **`findings` 是权威、`reasons` 是派生视图**：结构化改造最怕「两套数据各写各的」，故 `needReview(findings)`
   内部统一从 findings 派生 reasons，并有单测断言两者逐条一致（`reasonsAreDerivedFromFindingsConsistently`）。
2. **不改 `LLM_DECISION` 前缀**：它是 `FlowDecision` 文档化的既有前缀，既有消费方依赖该格式。
   结构化后 `ReviewFinding.toReason()` 对 `code=LLM_DECISION` 特判沿用该前缀，
   而不是顺手改成 `RISK_HIT:`（该 code 仍按 RISK_HIT 级别参与 triggerType 解析）。
3. **`AMOUNT_LIMIT` 命中保留双条语义**：产出 `OVER_LIMIT` 级别项（决定工单 triggerType：OVER_LIMIT 优先）
   + 一条 `RULE_FAIL` 级别项（规则侧展示）。这是既有契约，不能因结构化改造而丢。
4. **金额字段缺失时 gap 为 null，不强造**：避免前端展示「差额 null 元」。

**验证（AI 自测，非用户验收）**

| 项 | 方式 | 结果 |
|---|---|---|
| 结构 + 判定产出的结构化字段 | 新增 `ReviewFindingTest` | **11/11 通过**：item 级 finding 带「行号/标准/实际/差额」；单据级无行定位；单侧数值缺失时 gap 为 null；`toReason` 含行号与差额；`LLM_DECISION` 前缀保持；rule_check 命中给出行定位与数值；AMOUNT_LIMIT → OVER_LIMIT 级；重复硬命中 finding 带发票号与撞单号；票据不一致 finding 带票面/申报总额与差额；reasons 与 findings 逐条一致；AUTO_PASS 无 findings |
| 工单落库 | `AuditTicketServiceTest` | 4 处 `enterApproval` 调用同步；断言 `reviewFindings` 落库、triggerType 由 findings 的 level 解析、重跑复位刷新 findings |
| 既有断言同步 | `ReviewFlowDeciderTest` | 文案改由 findings 派生，断言改为「前缀 + 关键语义」（不再逐字比对文案） |
| 全量构建 | `mvn -o clean install`（19 模块） | **BUILD SUCCESS**；agent-core **135 例**（100 + R3 的 13+18 + R4 的 11 等）、tool-service 22 例 |
| 迁移 | 已在真实 `finaudit` 库执行 | 退出码 0，`review_findings` 列已建出（`json` 类型）；**重复执行退出码 0（幂等**，用 information_schema 判定后动态 DDL——MySQL 5.7 无 `ADD COLUMN IF NOT EXISTS`） |

**过程中修掉的一个真 bug**

`collectRuleHits` 的兜底判定我最初写成「若没有 `RULE_FAIL` 级别的 finding 才补一条」，
但 `AMOUNT_LIMIT` 命中产出的是 `OVER_LIMIT` 级别，于是漏判 → 多补一条重复的 `RULE_FAIL`。
已改为「没有任何具体命中项被处理（`!anyHit`）才补兜底」——与「`overLimit` 且无具体 hit」的原语义一致。

**⚠️ R4-7（端到端验收暴露的传输层字段丢失）：`RuleCheckTool` 手工装配 Map 漏传新字段**

首轮端到端跑出 `itemIndex=null / expected=null / itemName=""`（判据① 的 8 项里 6 项 FAIL）。
根因不在判定逻辑，而在**工具输出的装配层**：

- `RuleCheckTool` 用**手工装配 Map** 把 `RuleHitVO` 转成工具输出（不是整体序列化）：
  ```java
  m.put("ruleCode", h.ruleCode());
  m.put("ruleName", h.ruleName());
  m.put("ruleType", h.ruleType());
  m.put("message",  h.message());
  m.put("overLimit", h.overLimit());
  // ← R4 给 RuleHitVO 加了 itemIndex/itemName/expected/actual，这里没同步，字段在此被静默丢弃
  ```
- 后果：字段在 VO 里、在 agent-core 里都正确，**经过工具输出这一层就没了**，
  下游 `ReviewFlowDecider` 拿到 null → 结构化 findings 只剩 code/level/suggestion，定位与数值全失效。
- 修复：同步补上四个字段；并新增 `RuleCheckToolTest`（3 例）锁住「结构化字段必须透传」。
- **教训**：手工装配的传输层是「新增字段的静默黑洞」——它不会编译报错、也不会被 VO 的单测覆盖。
  **凡是给跨服务 DTO 加字段，必须全链路搜一遍手工装配点**（`m.put("xxx", ...)`）。
  本次靠端到端脚本才发现，单测当时完全没覆盖 `RuleCheckTool`。

> 另一个观察（非缺陷）：判据① 那次工单同时含 6 条 `DUPLICATE_INVOICE` 问题项，
> 因为复核脚本复用同一张样张，该票此前已被 6 张单报销过。
> 这说明按票硬命中在真实数据下工作正常；但也提示**同一张问题票会产出多条同类 finding**，
> 未来若前端清单过长可考虑按 code 折叠（已记入 R4-4 前端项）。


**待办**

- [x] 迁移已在真实库执行（`review_findings` 列）
- [ ] **重启 agent-core-service**（findings 产出 + 工单新列 + 规则结构化数值生效）
- [ ] 联调验收：构造一张超标单据 → 工单 `review_findings` 含明细行号/标准值/实际值/差额/建议
- [ ] **R4-4 / R4-5 前端延后**（按 AGENTS.md §7 后端先行）：报销单详情/工单详情「待修正项清单」、
      编辑页明细行标注问题项 + 预填建议值
- [ ] **R4-6 提交幂等未做**（见下）


### R5 · Agent 语义自校验与自主纠错（A-1，放大器；依赖 R2/R3 数据）—— ✅ 后端完成，见 §11

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R5-1 | 新增 `SelfConsistencyChecker`（5 条断言，第 ③④ 条来自 R3 的真实产出） | 新增 `domain/SelfCheckResult.java`、`service/SelfConsistencyChecker.java` | 单测：5 条断言各自命中/通过 ✅ 13/13 |
| R5-2 | 自校验接入收尾闸口（**设计偏差**：未做成流水线步骤，理由见 §11） | `AgentOrchestrator.java` | 收尾前强制校验，通过才允许 AUTO_PASS ✅ |
| R5-3 | 矛盾命中 → 重跑风控步骤（增强 prompt 携带矛盾提示），上限 1 次；超限 → `NEED_REVIEW` + findings | `AgentOrchestrator.java` | 单测：构造矛盾 → 重跑 1 次 → 仍矛盾则 NEED_REVIEW ✅ |
| R5-4 | `agent_task` 增 `correction_count`、`self_check_result` 列 + 迁移 + VO 透出 | `migration-P3.8.sql`、`AgentTask.java`、`TaskVO.java` | 前端任务详情可见"纠错 N 次 + 自校验明细" ✅ 列已建出 |
| R5-5 | **AUTO_PASS 基线实测**：构造 20~30 张小样本单据，统计自动通过率与各 reviewReason 分布，据此决定是否放宽 `confidence<0.7`（决策 5） | 测试脚本 + 结果记录 | ⬜ **未做**（需真实 LLM 与 OCR 配额，见 §11 R5 待办） |
| R5-6 | 联调缺陷：自纠错重跑误用 `continueTask` → 重入 `finalizeSuccess` → 无限递归 `StackOverflowError` | `AgentOrchestrator.resumeFromFirstPendingStep` | ✅ 直接分发首个 PENDING 步骤，不再重入收尾 |
| R5-7 | 加固：自校验/自纠错异常一律兜底放行，绝不阻断收尾（否则单据永久卡 RUNNING） | `AgentOrchestrator.passSelfCheckOrCorrect` | ✅ 单测 `AgentOrchestratorFinalizeIsolationTest` 3/3 |
| R5-8 | 联调缺陷：`resetForSelfCorrection` 只改 DB 未同步内存对象 | `AgentTaskStepService.java` | ✅ 回归护栏 `resetSyncsInMemoryStepState` |
| R5-9 | **联调缺陷（真正根因）**：wrapper `set(jsonColumn, value)` 不带 typeHandler → MySQL 5.7 以 binary 字符集拒绝写入，异常被兜底吞掉 → 自纠错链路从未执行 | `AgentTask`/`AgentTaskStep` 补丁工厂、`AgentTaskService.applySelfCheckResult`/`prepareRerun`、`AgentTaskStepService.updateInputParams` | ✅ 真实库最小复现 + `JsonColumnTypeHandlerGuardTest` 4/4 |
| R5-10 | 联调缺陷：矛盾提示注入到了 TOOL 步骤（风控角色下 `findFirst` 命中 `duplicate_check`），从未到达 LLM，重跑等于白跑 | `AgentOrchestrator.injectSelfCheckHint` | ✅ 只注入 `stepType=LLM` 的风控步骤，退化到首个 LLM 步骤 |
| R5-11 | 加固：自校验/自纠错轨迹落库（`result.selfCheckTrace`），摆脱「只能看 IDE 控制台日志」 | `AgentOrchestrator.trace`、`docs/test/r5-self-check-e2e.ps1` | ✅ 脚本新增判据 C：轨迹必须存在且不含「自校验执行失败/自纠错动作执行失败」 |

### R6 · 结构与契约（A-2 / A-4 / A-5、P1-1 / P1-4 / P1-5 / P1-6）—— 🔶 后端主体完成（R6-1 前端页登记延后）

| 序 | 动作 | 涉及文件 | 验收断言 |
|---|---|---|---|
| R6-1 | `GENERIC` 产品化：**接入自校验 + 高风险建审批工单**（前端「智能分析」页按 §7 仅登记，前端阶段实施） | `AgentOrchestrator.finalizeSuccess`、`SelfConsistencyChecker` | 单测 3 例（GENERIC 过自校验 / NEED_REVIEW 建单 / AUTO_PASS 收尾）+ `docs/test/r6-generic-task-e2e.ps1` 端到端 |
| R6-2 | `ToolAccessGuard` 租户基准改为**网关/JWT 派生租户**，与「声明租户」分离传入；MQ 链路改用任务归属反查 | `ToolTenantCredential`、`ToolAccessGuard`、`ToolRegistryService`、`ToolController`、`AgentCoreServiceFeign`、`InternalAuditDataController`、`AgentTaskService.findTenantIdByTask` | 单测 18 例：权威租户不一致拒绝、消息租户与任务归属不一致拒绝、无登录上下文的 HTTP 直调拒绝（fail-closed） |
| R6-3 | 工具缓存 Key 加租户前缀 `tool:exec:{tenantId}:{code}:{sha256}` + 读写 try/catch 降级 | `ToolExecutionService` | 单测 4 例：跨租户不同 key、Redis 读失败降级直连、写失败不影响回吐、命中缓存不重复执行 |
| R6-4 | `tool_registry` 增 `output_schema` 列 + 注册时校验 Schema 合法性/强度 + 出参校验 + 修正 `budget_query` 入参 Schema（deptName/deptId 二选一） | `ToolRegistry`、`ToolRegistryRegisterRequest`、`ToolRegistryService`、`migration-P3.8.sql` §13~15、`finaudit-schema.sql` | 单测 8 例：非法/无强度 Schema 注册被拒、出参不符拦截、无 Schema 兼容放行 |
| R6-5 | `RuleBasedFlowEngine` 声明式化（`FlowDefinition` + `FlowStepDefinition` + `StepType` 枚举，Java 内默认定义） | `domain/FlowDefinition.java`、`domain/FlowStepDefinition.java`、`enums/StepType.java`、`RuleBasedFlowEngine.java` | 单测 5 例：8 步声明顺序、票据核验紧跟规则校验、条件步骤按入参跳过、LLM 步骤无入参、物化无副作用 |
| R6-6 | 多 Agent 表述澄清：**「进程内角色化，非跨服务 A2A」** | `AgentRole.java` 注释、`docs/architecture/task-orchestration.md` §0 | 文档口径统一，避免夸大表述 |
| R6-7 | 补 `common-jwt-starter` / `common-mq-starter` README；新增 `docs/architecture/conventions.md`；README 与架构索引补规范入口 | 两个 Starter README、`docs/architecture/conventions.md`、`README.md`、`docs/architecture/README.md` | 贡献者从 README 一步可达约定正文 |

### R7 · 一致性与文档收口（P1-7 ~ P1-14、全部 D 类）—— 🔶 代码完成，文档同步进行中


**代码侧（R7-1 ~ R7-8 + 补做 P1-14）**

| 序 | 改动 | 关键点 |
|---|---|---|
| R7-1 | 环境变量凭据取消回退默认值 | 各服务 `application.yml` 的 `NACOS_USERNAME/PASSWORD`、`RABBITMQ_USERNAME/PASSWORD`、`MINIO_ACCESS_KEY/SECRET_KEY` 共 30 处去掉 `:nacos`/`:guest`/`:minioadmin` 回退 → **缺失即启动失败**；地址/端口类（HOST/PORT/ENDPOINT）保留本地拓扑缺省（非凭据、固定值）。`.env.example` 重写：统一 RabbitMQ 口径（旧版 yml 回退 `guest/guest` 与 `.env.example` 的 `admin/admin123456` 相互矛盾，是登记在案的漂移），逐项标注「必填」并说明 Spring Boot 不读 `.env`（需 IDE EnvFile 插件或容器注入） |
| R7-2 | 角色绑定校验 + 批量去重 | `SysUserService.validateRoleOwnership`：绑定/创建用户时校验 roleId 均属当前租户（经 `SysRoleService.getByIds` + 多租户拦截器，数量不符即拒），堵住「本租户用户 → 他租户角色」的越权映射行；`SysUserRoleService.replaceRoles` 入参 `distinct()`，避免重复 roleId 撞 `uk(user_id, role_id)` 把正常输入升级成 500 |
| R7-3 | Feign 全局超时 | 新增 `FeignTimeoutProperties`（前缀 `finaudit.feign`，缺省 建连 3s / 读 10s）与 `Request.Options` Bean。此前全后端无任何 Feign 超时配置 → 用框架缺省 **读超时 60 秒**：对端假死会把调用方线程占满一分钟，在 agent-core 上连带拖停 MQ 消费线程与任务推进，表现为「任务长期 RUNNING、无错误日志」 |
| R7-4 | 任务可见性 null 修复 + 形参改名 | `requireVisible`：`userId == null` 时不再 NPE（原先 `userId.equals(...)` → 500），按「无法证明是本人 → 拒绝」返回 403 业务语义；形参 `finance` → `viewAll`（判定依据是权限码 `task:viewAll`，按角色命名会把权限语义与角色焊死） |
| R7-5 | 部门写操作事务 + 出参 VO + 实体转换 | `SysDeptService.create/update/delete` 加 `@Transactional`；`SysDept` 新增 `from(request, tenantId, parentId)` / `apply(request)` 与 `STATUS_ENABLED` 常量（此前 create 在 Service 里手写 set 组装实体，违反「转换封装在实体类」约定）；`SysDeptController` 出参改 `DeptVO`（原先直接回实体，会把 `tenantId/deleted/createdAt` 等内部字段暴露给前端）；字段合并（apply）与校验（查库做唯一性/防环）职责分到实体与 Service 两侧 |
| R7-6 | 上传大小校验（后端侧） | `FileService.upload` 增加 20MB 应用层校验并给出可读提示（容器层 `max-file-size: 20MB` 抛的是 `MaxUploadSizeExceededException` → 500，前端只能看到"上传失败"）；三处口径（前端文案 / yml / 常量）对齐。**前端 `before-upload` 按 §7 只登记，前端阶段实施** |
| R7-7 | 删死代码 + 登记删除接口 TODO | 删除 `FileService.validateAllOwned`（全仓无调用者且自身缺归属校验；附件归属校验已由 `AttachmentService.rebindForReimb` + file-service 内聚合承担）；`FileController` 末尾登记「文件删除/回收接口」TODO，并写清实现前必须先定的三件事（语义是物理删还是回收站 / 权限口径 / 跨服务引用完整性） |
| R7-8 | MQ 发送与消费的可靠性可观测 | 两服务 yml 增 `publisher-confirm-type: correlated` + `publisher-returns: true` + `listener.simple.retry`（3 次、指数退避 1s→2s→4s、上限 10s）；`CommonMqAutoConfiguration` 增 `RabbitTemplateCustomizer`（nack / mandatory return 打 ERROR）与 DLQ 告警消费者 `DlqAlertConsumer`（此前 DLQ **无任何消费者**，消息进 DLQ 即掉进黑洞，只能靠人工翻管理台；现打印 `x-death` 死亡原因 + 截断 body）。⚠️ 已知取舍：消费者会 ACK 移除 DLQ 消息，日志非持久化存储，生产应改用 Shovel/独立死信库或接告警通道（已登记 P4/B-8） |
| **补做** | **P1-14（原计划未落到任何 R 项，属 R7 范围漏项）** | ① `/actuator/info` 口径澄清：yml 注释与代码不一致（注释称"health/info 做存活探测"，白名单实际只放行 `health`）→ 保留更严行为、修正注释；② 删除网关白名单中 `swagger-ui/**`、`swagger-ui.html`、`v3/api-docs/**`、`webjars/**` 四条**死分支**（网关既无 springdoc 依赖也无对应路由谓词，永远匹配不到，留着只会让人误以为网关对外暴露了接口文档；各服务 Swagger 直连服务端口） |

**R7-9 / R7-10（文档与仓库收口，D-1 ~ D-16 全量同步）**

| 项 | 结果 |
|---|---|
| D-1/2/3/10 `docs/api/tool-service.md` | 三个工具结果形状改为**代码真实形状**（`ocr_extract` 字段在 `receipts[].fields`；`duplicate_check` 的 `suspected` 是布尔、清单在 `duplicates[]`、权威分级看 `dupLevel`；`budget_query` **未配置时无 `remaining`**）；补齐 `tool:manage`/`tool:execute` 权限码与注册请求的 `scenario`/`cacheable`/`outputSchema`；新增「工具执行链五道关卡」章 |
| D-4 `docs/api/README.md` | 新增「错误语义」节：**业务错误恒 HTTP 200 + body.code**，并如实区分三个非 200 例外（网关 401 / 权限拦截器 403 / 容器层不保证 `R<T>`） |
| D-5 `docs/api/tenant-service.md` | 补 3 个已实现端点（`GET /permissions`、`GET\|PUT /roles/{id}/permissions`）、登录响应 `perms`（示例即 admin 的 24 项真实权限码）、`deptId`/`deptName`、各章权限码清单 |
| D-6 `docs/api/file-service.md` | 新增 P3.5c 归属校验专章（用户侧 `requireReadable` vs 内部侧仅租户隔离）；纠正批量语义（对外批量端点已随 R0-3 移除）；讲清 `Content-Disposition` 在**预签名 URL 的查询参数**里、由 SDK 生成 |
| D-7/D-8 `docs/architecture/task-orchestration.md` | 补工具级 8 步顺序（显式标注 `amount_verify` 在 `rule_check` 之前）；工具执行链补**入参/出参 Schema 校验与四道防越权守卫**；审批动作表述改为 `@RequirePerm("audit:approve")` + JWT 权限快照（删净「X-User-Roles 含 admin/auditor」） |
| D-9 `docs/architecture/tenant-auth.md` | 忽略表补 `sys_permission`；登录改写为 P3.5d 的 10 步顺序（密码优先、禁用后置）；补 `perms` |
| D-11 `docs/planning/P3.5-execution-plan.md` | §5 R3 按实际勾选（附行号依据）；「联调验收」**有意不勾**并说明理由；如实标注「部门选择器实现为 el-select + 扁平化树选项，非原文的 el-tree-select」 |
| D-12 `docs/api/rag-service.md`（+`gateway.md` 补注） | 新增「经网关不可达」章（路由表无 9204 + `discovery.locator.enabled:false`）；给出「P4 新增接口必须同时补网关路由」的硬要求 |
| D-13 | **登记项本身已随 R0-1 解决**（见 §3 表格标注）；R7 复核仅修两处措辞并补记 token 请求同受超时约束 |
| D-14 | `common-model-starter/README.md` + 两处 Javadoc 注释（`ChatClientFactory`、`ToolServiceApplication`）：删陈旧 TODO，改为当前事实，并**如实标注**「仅注册 DeepSeek + `fallback-type` 缺省 null ⇒ 备用模型切换默认不生效」 |
| D-15 | 修掉指向**不入库**文件的死引用（README「目录结构见 CLAUDE.md」、`conventions.md` 头部「正文在 AGENTS.md」），明确「对克隆本仓的贡献者，`conventions.md` 就是规范正文」；未改 `.gitignore`、未把 `AGENTS.md`/`CLAUDE.md` 入库（维持 `6502641` 决策） |
| D-16 `docs/deploy/README.md` | 从 3 行占位补成 8 节完整文档：`.env` 准备（含 R7-1 的 fail-fast 语义与完整变量清单、「Spring Boot 不读 .env」专节）、Nacos 初始化、**增量迁移执行顺序 + 逐脚本幂等性表**（如实标注 `migration-P3a/P3b.sql` 内含「仅可执行一次」的 ALTER，重跑报 Duplicate column）、启动顺序与端口、8 项故障排查（含 `Could not resolve placeholder 'NACOS_PASSWORD'` 的两种查法） |
| D-16 `docs/test/README.md` | 从 3 行占位补成 7 节：10 个脚本逐个清单（覆盖/判据/退出码）、参数用法、**UTF-8 BOM 专章**（含 `EF BB BF` 复核与 Parser 语法自检）、配额成本注意、**评估用例模板**（供 P4 量化评估） |
| R7-10 仓库清理 | `backend/rag-service/target` 构建残留已清理（`target/` 本就未入库，`.gitignore` 有 `backend/**/target/`） |

**R7-9 复核中额外发现并修复的两处「代码内」不一致**（原不在 D 项范围，属本阶段一致性收口）：
1. `AgentRole.RULE_VALIDATOR` 声明工具集漏了 `invoice_match`，而 `FlowDefinition` 把该步骤绑给 RULE_VALIDATOR
   → 已补齐为 `{rule_check, amount_verify, invoice_match}`；
2. `BaiduOcrService` 注释称其超时做法与 `OcrExtractTool`「同款」，实际口径不同（前者 connect/read 同值，
   后者 connect 10s / read 60s）→ 已改写注释说明差异与各自理由。

⚠️ **遗留（不在 R7 范围，登记待 R9/后续）**：`docs/database/README.md` 仍是 3 行占位
（迁移顺序说明已落在 `docs/deploy/README.md`）。

**本机验证**：`mvn -o clean install` 19 模块 BUILD SUCCESS；tenant-service 25 例（含新增 `SysUserRoleServiceTest` 5 例）、
agent-core 192 例（含新增 `AgentTaskVisibilityTest` 5 例）、tool-service 45 例全绿。
⚠️ **R7-1 的启动行为需用户复验**：凭据环境变量缺失时服务应启动失败（用户 `.env` 已含全部凭据键，故预期可直接启动成功）。



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

### R8 · 可选增强 —— 🔶 R8-3 后端完成并运行时复验通过（**待提交**），R8-1 待商务决策、R8-2 未开始、R8-4 归前端

**R8-3 进度百分比与预计等待时间（✅ 后端完成并运行时复验通过）**

| 落点 | 要点 |
|---|---|
| `AgentTaskStepMapper.xml` 新增 `avgDurationByStepKey` | 按「步骤类型 + 工具编码 + **执行角色**」统计最近 **30 天**内 `SUCCESS` 且 `duration_ms` 非空的步骤平均耗时；数据源即 R9-2 落库的真实墙钟耗时。⚠️ 角色参与分组是实测结论：同属 LLM 的风控判断(1600ms)与结论汇总(2076ms)差约 30%，只按 step_type 分组会给错基线 |
| 新增 `TaskProgressService` | 进度百分比（`finished/total`）、当前步骤、已耗时、**预计剩余 = 未完成步骤逐项累加同类历史平均耗时**；无样本退化为缺省值（TOOL 1500ms / LLM 2500ms）并在 `estimateSource` 如实标注 `DEFAULT`；基线查询失败**不报错**、自动退化 |
| 新增 `TaskProgressVO` | 轻量出参（不含 `result`/`selfCheckResult` 等重字段），适合 2~3 秒轮询；含 `correctionCount`（见下文"进度回退"发现） |
| `GET /api/v1/tasks/{id}/progress` | `AgentTaskController` 单次委托 `AgentTaskService.progress`（可见性校验 + 计算合并，符合 §5.12） |
| 契约文档 | `docs/api/agent-core.md` 新增该端点小节，含字段口径表与五条要点（基线来源、DEFAULT 需提示粗略估算、**终态人工等待不计入**、失败退化、**进度会回退及前端处置**） |
| 验证脚本 | 新增 `docs/test/r8-progress-endpoint-check.ps1`：判据 A 契约字段 / B 运行中快照自洽 / **C ETA 与库内基线逐键同口径**（对每个 RUNNING 快照重算期望值比对，非抽查）/ D 终态定格与落库耗时一致；`-TaskId` 可复检不消耗配额 |

**设计取舍**：ETA 刻意**不做花哨模型**（如按 token 回归）——流水线耗时主要由模型调用决定、抖动大，
样本不足时复杂模型反而更不准；用统计均值 + 明示依据（HISTORY/DEFAULT/FIXED）是本阶段性价比最高、
也最容易解释的做法。分桶键由**同一个函数**生成（统计与取用共用 `key(...)`），避免口径漂移导致
"永远命中不到基线、静默退化为缺省值"这类难查的 bug。

**本机验证**：`TaskProgressServiceTest` 9/9（有样本走 HISTORY 并给出 3300ms、无样本走 DEFAULT、
终态不再估算且取实际 `duration_ms`、无步骤不除零、基线查询失败不报错、待审态视为流水线已结束、
`correctionCount` 透出与 NULL 归 0、**收尾判定窗口**）；全量 `mvn -o clean install` 19 模块 BUILD SUCCESS、
**342 用例 0 失败**（agent-core 210 / tool 45 / tenant 25 / common-code 27 / 其余 starter 与 file-service）；
新增 SQL 已在真实库执行验证（返回 `ocr_extract 4492ms`（最慢）、LLM 按角色 1600/2076ms 等真实基线）。

> ✅ **验证边界已闭环**：上述运行时证据覆盖的是**加 `correctionCount` 之前**的端点版本；
> 用户重启 agent-core 后已完成复验（见下「第二轮运行时复验」），不再是待办项。

**第二轮运行时复验（用户重启 agent-core 后，`correctionCount` 上线）**

| 检查 | 结果 |
|---|---|
| 字段上线且**值与库内一致** | `30003`(库 0→端点 0)、`30004`(0→0)、`30005`(**1→1**，即发生回退的那单) |
| 终态复检（`-TaskId 30005/30003/30006`，零配额） | 三次均 **PASS=14 FAIL=0 SKIP=2**，`elapsedMs` 与库内 `duration_ms` 逐单一致（16977/7708/11207） |
| 全量模式（`taskId=30006`） | 采集 27 次；**「回退 + `correctionCount=1`」判据分支首次真跑并 PASS**；库内 `correction_count=1`、`coherent=false` 印证回退确由自主纠错重跑触发 |
| 库内事实 | `30006` 8 步全 SUCCESS、`duration_ms=11207` 与端点一致 |

**第二轮暴露的新状态：收尾判定窗口（8 步全成功但任务仍 RUNNING）**

同一轮全量跑出现 3 条 FAIL，全部来自同一个快照：

```
RUNNING pct=100.0 步=8/8 当前=(空) 已耗时=7471ms 剩余=0ms 依据=DEFAULT 样本=0 步骤已全部执行，正在收尾判定
```

这是「全部步骤 SUCCESS、任务尚未迁到终态」的**收尾判定窗口**（终态迁移与自校验之间，实测约 1~2 秒），
端点行为正确（`currentStepName` 为空、剩余是确定的 0、文案自带解释），**是首版脚本的断言过严**——
它默认「RUNNING ⇒ 必有未完成步骤」。处置（避免把正常状态报成缺陷，与踩坑清单第 18/23 条同源）：

1. 脚本判据改为**与「未完成步骤数」等价**：有剩余 → `currentStepName` 非空 / `message` 形如「第 N/M 步：…」/ 剩余 > 0；
   无剩余 → 三者反向，且 `message` 恰为「步骤已全部执行，正在收尾判定」；并单独断言「步骤尚未生成」的 PENDING 期
   （进度 0、剩余 0、无当前步骤、`message` 即状态文案）。观察到收尾窗口时打印说明而非只报数。
2. `TaskProgressServiceTest` 补第 9 例 `allStepsDoneButStillRunningIsFinalizingWindow` 钉住该状态，防止被误"修"。
3. `docs/api/agent-core.md` 补要点 6 描述该状态；并把要点 2 写清 **`estimateSource=DEFAULT` 的两种情形**
   （无样本推算 / 无待估算项），要求前端仅在 `DEFAULT && estimatedRemainingMs > 0` 时提示"粗略估算"；
   同时明确**判断是否结束只看 `status`，不要用 `estimateSource`**。

**运行时验证（用户已重启 agent-core，端到端实测通过）**：提交一张新报销单（`taskId=30003`），
以 400ms 间隔轮询 `/progress` 共 18 次、去重后 6 个状态快照，三条路径全部命中：

| status | progressPct | finished/total | currentStepName | elapsedMs | estimatedRemainingMs | estimateSource | samples |
|---|---|---|---|---|---|---|---|
| PENDING | 0.0 | 0/0 | — | 374 | 0 | DEFAULT | 0 |
| RUNNING | 0.0 | 0/8 | 票据解析 | 835 | 8697 | HISTORY | 16 |
| RUNNING | 37.5 | 3/8 | 规则校验 | 3533 | 3883 | HISTORY | 10 |
| RUNNING | 75.0 | 6/8 | 风控语义判断 | 3983 | 3675 | HISTORY | 4 |
| RUNNING | 87.5 | 7/8 | 审核结论汇总 | 6221 | 2076 | HISTORY | 2 |
| APPROVAL_PENDING | 100.0 | 8/8 | — | 7708 | 0 | FIXED | 0 |

- **百分比与当前步骤随实际执行推进**：`3/8=37.5%`、`6/8=75%`、`7/8=87.5%`，`currentStepName` 取未完成步骤的首个并带序号文案。
- **ETA 与历史基线逐项对齐（关键证据）**：`7/8` 时的 `estimatedRemainingMs=2076` **恰好等于本次运行前** `LLM + SCHEDULER` 分组的历史均值（该键当时 2 个样本，即 `samples=2`）；本次运行落库 1450ms 后该键均值被拉低到 1867ms（3 样本），说明端点取的是**本次运行之前**的基线，与统计 SQL 同口径、非巧合命中。`samples` 随未完成步骤数递减（16→10→4→2），符合"只统计剩余步骤涉及的键"设计。
- **终态定格**：进入 `APPROVAL_PENDING` 后不再估算，`elapsedMs=7708` 与库内 `agent_task.duration_ms=7708` 完全一致（8 步 `duration_ms` 全部落库），`estimateSource=FIXED`、`estimatedRemainingMs=0`、文案「已完成（待人工复核）」。
- **接口契约**：`Content-Type: application/json`，响应体 UTF-8 正常，出参为轻量 VO（不含 `result`/`selfCheckResult`）。

**脚本化复验（`r8-progress-endpoint-check.ps1`，全量模式 `taskId=30004`）**：`PASS=104 FAIL=0 SKIP=0`，
对 27 个 RUNNING 快照逐个核对 ETA：**每一档进度的 `estimatedRemainingMs` 与库内逐键合计差 0ms**、
`samples` 逐个相等（`pct=0% 期望 8421ms/样本 24`、`50% 3800ms/12`、`75% 3639ms/6`、`87.5% 1867ms/3`）。

### ⚠️ 运行时发现的真问题：`progressPct` 会回退（已修复为"可解释"）

第二次脚本化全量复验（`taskId=30005`）捕获到 **`progressPct` 回退**：`87.5% → 62.5%` 后再爬回 `100%`，
末次汇总 `PASS=112 FAIL=1`（该 FAIL 即"全程单调不减"断言）。**这不是脚本误判、也不是端点缺陷**，
库内证据链如下：

| 证据 | 事实 |
|---|---|
| 轮询序列 | `7/8`（当前=审核结论汇总）→ `5/8`（当前=重复报销检测）→ `6/8` → `7/8` → 终态 |
| `agent_task_step`（含软删全查） | 仍是 **8 行、`deleted=0`、无 replan 新增行**（id 33~40），步骤 6/7/8 的 `duration_ms` 为最后一次执行值 |
| `agent_task` | `correction_count=1`、`self_check_result.coherent=false` → 走了 R5 自校验纠错分支 |

**机制**：自校验判定不一致 → 自主纠错重跑风险相关步骤（步骤 6~8）→ 这些步骤状态由 `SUCCESS` **复位**为
`PENDING`/`RUNNING` → `finishedSteps` 真实减少 → 百分比回退。**端点如实反映真实状态是正确行为**
（不能为了让进度条好看而谎报单调递增）。

**处置（本阶段完成的部分）**：
1. `TaskProgressVO` 新增 `correctionCount`（取 `agent_task.correction_count`，NULL 归 0）——
   让「回退」有唯一合法解释，前端可显示「正在重新核验（第 N 次纠错）」；新增 2 张单测钉住。
2. 验证脚本把"单调不减"改为**机制感知判据**：无回退 → PASS；有回退且 `correctionCount ≥ 1` → PASS（并打印每次回退的档位/步骤/耗时）；
   有回退却 `correctionCount = 0` → FAIL（无机制可解释，须排查）；响应缺该字段 → SKIP（agent-core 未重启）。
3. `docs/api/agent-core.md` 写入回退语义与**前端处置要求**（见下 R8-4）。

**为什么这值得单独记账**：这类"回退"最容易被当成前端 bug 或端点 bug 反复排查；把它固化成
「有机制解释 + 有字段可查 + 有脚本判据」三件套，是 R8-3 之外顺带拿到的可靠性收益。

**顺带修复（同一阶段内联调暴露）**：`docs/test/*.ps1` 的中文断言曾因 `Content-Type: application/json`
不带 charset 而被 PS 5.1 按 Latin-1 解码成乱码（R8-3 首轮出现假 FAIL「终态文案可读」）。
产品侧无需改动（浏览器/Jackson/Feign 对 JSON 默认按 UTF-8）；已在 `r8-progress-endpoint-check.ps1` 与
`bizno-collision-retry.ps1` 的 `Api()` 中改为**显式按 UTF-8 解码原始字节**（后者若不修，诊断分支的
两个 `-match` 会都不命中，把"连续撞号后放弃重试"误报成"3 次造撞均未得到成功提交"）。

**R8-1 需要业务方决策的 6 项**（决策前保持现状：离线规则验真，`invoice_match` 的 R3-5 段）：
① 是否引入外部查验（唯一有真金白银成本的项）；② 走官方查验平台（免费但无开放 API、有人机校验、程序化调用有合规风险）
还是商业服务商（有正式 API、按次计费，**具体厂商与价格需商务渠道确认，我无法核实**）；
③ 预算与调用量（建议只对"离线规则存疑"的票联网查验）；④ 合规与数据外发（发票要素出网是否允许、是否需签数据处理协议）；
⑤ 失败降级（建议：联网失败不阻断审核，仅并入 findings，离线规则仍为主判据）；⑥ 缓存与幂等（同票号只查一次）。
决策后的实现量约与 R3-5 同级：新增查验工具 + **服务商适配器接口**（便于换厂商）+ 结果落库/缓存 + 工具注册与 Schema + 单测（mock 适配器）。



| 序 | 动作 | 状态 |
|---|---|---|
| R8-1 | 离线规则验真升级为外部查验服务商对接（**待商务决策**，见 §4.3 决策 2） | ⏸ 待决策（6 项决策点见 §11 R8 记录；决策前保持离线规则验真） |
| R8-2 | 主动通知：站内信 / Webhook（B-8） | ⬜ 未开始（后端可做） |
| **R8-3** | **进度百分比与预计等待时间（进度查询体验）** | ✅ 后端完成 + 运行时复验通过（**待提交**，见 §11 R8 记录）。含运行时发现的「进度回退」处置与 `correctionCount` 透出 |
| R8-4 | `reimbursement/list.vue` 补轮询 | ⏸ 归前端阶段（后端进度端点已就绪，见 R8-3）。**前端必须同时处理进度回退**：`correctionCount > 0` 且 `progressPct` 回退时显示「正在重新核验（第 N 次纠错）」；若产品要求进度条只增不减，须由前端对**展示值**取历史最大（`max(seen)`），后端仍返回真实值；`estimateSource=DEFAULT` 时要提示"粗略估算" |

### R9 · P4 前置（只做数据源，不建大盘）—— ✅ 运行时复验通过（已提交 `ecd484c`，双仓已推送）

| 序 | 动作 | 落地 | 验收 |
|---|---|---|---|
| R9-1 | **Token 用量落库 + `usageSnapshot()` 接出口** | 新增 `model_call_log` 表（迁移 §16，幂等 `CREATE TABLE IF NOT EXISTS`）；`common-model-starter` 新增 `ModelCallRecord` / `ModelCallRecorder`（SPI）/ `ModelCallContext`（线程内调用上下文）/ `ModelUsageSnapshot`（快照出参），`DefaultChatClientFactory` 每次调用测量耗时并回调 recorder + 打结构化日志（`[model-call] key=value`），`ChatClientFactory#usageSnapshot()` 成为接口默认方法（不统计的实现返回空快照而非 null）；agent-core 实现 `ModelCallLogService`（实现 SPI 落库，全程兜底不抛）+ `ModelCallLogMapper`；编排器 `executeLlmStep` 与 `TaskPlanner.plan` 分别以 `scene=llm_step` / `task_plan` 绑定上下文；**接出口**：新增内部端点 `GET /internal/metrics/model-usage`（snapshot + 台账 window 聚合，不经网关暴露） | 单测 13 例（starter 12 + agent-core 6 中的台账 6 例，含上下文清理/嵌套、成功/失败/切换三条记账路径、租户兜底、落库失败不阻断、快照失败率）；表已建于真实库（22 张表） |
| R9-2 | **任务/步骤耗时落库** | `agent_task.duration_ms`（本次执行 `started_at`→终态；进入 `APPROVAL_PENDING` 即定格，**人工等待不计入**）、`agent_task_step.duration_ms`（LLM=模型调用耗时含切换；TOOL=分发置 RUNNING→`tool.result` 回调，含 MQ 往返）；`markSuccess`/`markApprovalPending`/`markFailed` 三处终态统一落耗时，`TaskVO`/`StepVO` 透出 | 迁移 §17 已执行（两列就位）；指标 SQL 实测可跑（含 `COUNT(duration_ms)` 而非 `COUNT(*)` 的分母口径） |
| R9-3 | **指标口径文档** | 新增 `docs/architecture/metrics.md`：成本（§1）、效率（§2）、风控（§3）三类 + 四项财务专属指标（自动通过率、重复报销拦截率、预算超支预警次数、票据识别成功率）的字段映射与**可直接执行的 SQL**；含三个前提（成本指标从 R9-1 上线起才有数据、耗时需 `COUNT(duration_ms)`、人工等待不计入效率）与**尚未采集指标的诚实清单** | SQL 逐条在真实库执行验证（`invoice_reimb_link` 无票号列 → 已改为 join `invoice_record`）；与 `ProjectRequirements.md` §七 的对照表 |

**设计取舍（重要）**
- **为什么建 `model_call_log` 表而不是给 `agent_task_step` 加列**：一次步骤可能多次调用模型（结构化输出解析失败会重试一次、
  故障会切备用模型），加列只能存最后一条，成本与失败率都会算不准。台账是「一次调用一行」的事实表。
- **为什么 Starter 只定义 SPI 不直接落库**：`common-model-starter` 是纯能力 Starter（无 MyBatis/数据源依赖），
  记账属于业务台账。谁关心成本谁实现 SPI（当前 agent-core 实现）；不实现的服务的 `ObjectProvider` 取不到 bean，
  工厂走 `recorder == null` 分支，不被迫引入数据源。
- **为什么用 ThreadLocal 传上下文而不是改 `AiClient` 签名**：`chatWithUsage(system, user)` 是全链路通用抽象，
  为记账加 4 个参数会污染所有实现与调用方；调用是同步的，且与本仓 `TenantContextHolder` 风格一致。
  已单测覆盖「异常路径也清理」与「内层结束恢复外层」两个易错点。

**⚠️ 执行顺序（已由 AI 在真实库执行）**：迁移 §16~18 → 重启 **agent-core-service**（仅它改了）；
`model_call_log` 无历史数据可回填（内存计数已随进程重启丢失），故**成本指标的起始时间即本阶段上线时刻**。

**R9 实施中发现并修复的框架级缺陷（R2-10 遗留关闭）**

实现 TOOL 步骤耗时时发现：`AgentTaskStep.updatedAt` **没有** `@TableField(fill = FieldFill.INSERT_UPDATE)`——
这正是 R2-10 登记但只修了 `invoice_record`/`invoice_reimb_link` 的遗留。后果有两层：

1. **耗时口径失真**：`markRunning` 走实体更新，实体里从库里读出的**旧** `updated_at` 会被写回 SET，
   抑制 MySQL 的 `ON UPDATE CURRENT_TIMESTAMP`（列一旦被显式赋值就不再自动更新）。
   于是 `onToolResult` 里的「现在 − step.updated_at」根本不是工具耗时，而是**从该行上次被更新算起的全部时间**（严重虚高）。
2. **`updated_at` 长期不动**：全仓 16 个带 `updatedAt` 的实体只有 2 个标了 fill；
   其余（`agent_task`/`agent_task_step`/`audit_ticket`/`budget_occupancy`/`file_record`/`sys_*` 等）
   更新时都不会刷新该列——DDL 声明的 `ON UPDATE CURRENT_TIMESTAMP` 形同虚设。

**修复**：为 15 个实体类（`RuleVO` 非实体，跳过）统一补 `@TableField(fill = FieldFill.INSERT_UPDATE)`。
`AuditTimestampMetaObjectHandler.updateFill` 已按 `FieldFill` 守卫并用 `setFieldValByName` 覆盖旧值，故补标注即生效。

**顺带加固（防同类问题再次静默污染指标）**：新增 `resolveToolDurationMs` 护栏——墙钟耗时远大于
工具自报 `tool_execution_log.cost_time_ms`（`> 5× + 60s`）时判定基准不可信，改用工具自报值并打 WARN。
指标列被悄悄写错比抛异常更难发现，宁可变保守值也要留痕。已补 2 条单测（正常基准走墙钟 / 陈旧基准走退化）。

**运行时复验证据（2026-09-22 02:17，agent-core 重启后；`r9-metrics-datasource-check.ps1` 15/15 PASS）**

| 判据 | 结果 |
|---|---|
| A 结构就绪 | `model_call_log` 表（15 列）+ `agent_task`/`agent_task_step` 两个 `duration_ms` 列 ✓ |
| B 台账有数（taskId=430692） | **4 行**、全部 `scene=llm_step`、tokens 合计 **12480**、4 行均带 `step_id` ✓ |
| C 耗时落库 | 任务 `duration_ms=14081ms`；LLM 步骤 2619/1796ms；TOOL 步骤 3894（OCR，最慢）/63/470/74/70/201ms —— 数值合理，**证明确认修复后的墙钟基准不再虚高** |
| D 指标 SQL | `metrics.md` 的 6 条 SQL 全部可执行 ✓ |
| E 接出口 | `GET /internal/metrics/model-usage` 返回 snapshot（calls=4, totalTokens=12480）+ window（同口径）✓ |

**台账「一次步骤多行」语义实测确认**：step_id 643（第 7 步风控）/644（第 8 步汇总）各出现 **2 次** ——
首跑 2 次 + **自校验命中矛盾后重跑 2 次**（该任务 `correction_count=1`，`self_check_result.coherent=false`）。
这正是"不做成 step 扩展列"的理由，也让**纠错重跑的成本可被单独量化**（P4 可直接按 `step_id` 计数 >1 聚合）。

**回归（本轮改了 15 个实体的 `updated_at` 填充，影响面较宽，故逐项验证）**

| 脚本 | 结果 |
|---|---|
| `r2-audit-timestamp-check.ps1`（最相关：直接断言 `updated_at` 跳变） | **2/2 PASS**：`seen_count 35→36`、`updated_at 02:17:58 → 02:18:03` |
| R2-10 关闭的直接证据（真实数据） | `agent_task.updated_at` 比 `created_at` 晚 **15s**（修复前恒等）；步骤 1/7 分别晚 4s/13s |
| `r5-amend-rerun-e2e.ps1`（写 `agent_task`/`audit_ticket`，两个新标注实体） | **19/19 PASS / 0 SKIP** |
| `budget-occupancy-concurrency.ps1` | 7/7 全部通过 |
| `bizno-collision-retry.ps1` | 4/4 PASS |
| 台账累计（多次运行后） | 16 行 / 43493 tokens；LLM 步骤 8 样本 avg 1877ms、TOOL 24 样本 avg 1312ms（数值合理） |

**待办**
- [ ] P4 建看板时按 `docs/architecture/metrics.md` 取值；票据识别**准确率**需先建人工标注对照集（模板见 `docs/test/README.md`）

**R9 实施中用「真实库最小复现」提前拆掉的风险（未重启即发现）**

新增 `docs/test/repro/ModelCallLogInsertRepro.java`：用真实 Mapper + 真实库把台账写入路径跑一遍
（mock 单测只能验证"传了什么"，验证不了列名映射 / NOT NULL / 长度）。覆盖 5 种记录形态
（成功 / 失败 / 走备用模型 / 超长错误信息 / 空可选字段）：**5/5 写入成功、回读一致、清理干净**。

过程中抓到 1 个真实缺陷 + 1 个流程陷阱：

1. **缺陷（已修）**：900 字符的 `error_msg` → `Data too long for column 'error_msg'`。
   Starter 侧虽有 480 字符截断，但只覆盖「经模型工厂回调」这一条路径；而调用方的兜底 catch
   会让整行台账**被静默丢弃**——失败调用的台账恰恰最需要留痕。
   已在 `ModelCallLog.from(...)`（知道列长的实体边界）补第二道截断（`ERROR_MSG_MAX_LEN = 500`）并补单测。
2. **流程陷阱（已写入复现程序用法注释）**：**必须先 `clean compile`**。增量编译会因「class 比 source 新」
   而跳过重编，于是跑的是旧字节码——现象是"改好的截断逻辑不生效"，**极易误判为产品缺陷**（本轮差点如此）。




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
    —— 🔶 后端已就绪（`POST /api/v1/tasks` + GENERIC 自校验/结果分支/建单，`r6-generic-task-e2e.ps1` 待复验）；前端页登记在前端阶段
12. **工具契约对称**：`budget_query` 传 `deptId` 不再被 Schema 拦（迁移 §14 已改 anyOf 二选一）；
    `output_schema` 存在时出参被校验；`ToolAccessGuard` 租户校验可被真实触发
    —— ✅ 代码与单测完成（`ToolSchemaContractTest` 8 例 / `ToolAccessGuardTest` 18 例），待迁移 §13 后运行时复验

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

### 阶段总览（截至 2026-09-12）

分支 `refactor/agent-autonomy-hardening`，**一阶段一提交**，双仓（gitee / github）已同步。

| 阶段 | 状态 | 提交 | 说明 |
|---|---|---|---|
| **R0** 主链路止血 | ✅ 完成并推送 | `3114b60` | 登录 500、OCR 超时死配置、tool.result 丢失、内部契约收口、4 个前端缺陷 |
| **R1** 预算真实占用与释放 | ✅ 完成并推送 | `d8f1586` | `budget_occupancy` 记账表 + 原子占用 SQL；含 R1-8 单号撞库、R1-9 配平公式两个联调缺陷 |
| **R2** 发票标识符入链 | ✅ 完成并推送 | `1acaa9a` | 票号入链 + `invoice_record` 投影表；含 R2-10 审计时间戳填充框架缺陷 |
| **R3** 按票查重 + 票据核验 | ✅ 完成并推送 | `a4df2e7` | 两级查重 + `invoice_match` 工具；含 R3-7 一票多单归属的**架构缺陷**修复 |
| **R4a** 结构化 findings | ✅ 完成并推送 | `1a9375b` | `ReviewFinding` + `review_findings` 列；含 R4-7 传输层字段丢失 |
| **R5** 语义自校验与自主纠错 | ✅ 运行时复验通过（**未提交**） | — | 5 条一致性断言 + 矛盾重跑风控；含 R5-6/R5-8/R5-9/R5-10/R5-11 五个联调缺陷（**R5-9 为真正根因：JSON 列写入方式错误**），端到端脚本 9/9 PASS |
| **R6** 结构与契约 | ✅ 运行时复验通过（**未提交**） | — | GENERIC 产品化（自校验 + 高风险建单）/ 工具租户基准重做 / 缓存租户隔离 / 出参 Schema / 声明式流水线 / 多 Agent 口径澄清 / conventions.md + 两个 Starter README |
| **R7** 一致性与文档收口 | 🔶 代码完成，文档同步进行中（**未提交**） | — | 凭据取消回退默认值(fail-fast) / 角色归属校验+批量去重 / Feign 全局超时 / 可见性 null 修复 / 部门事务+VO+实体转换 / 上传大小校验 / 删死代码 / MQ confirm+DLQ 告警+退避重试；**补做 P1-14（计划漏项）** |
| R4-4 / R4-5 前端展示 | ⏸ 延后（§7 后端先行） | — | 待修正项清单、编辑页标红预填 |
| R6-1 前端「智能分析」页 | ⏸ 延后（§7 后端先行） | — | 后端已就绪：`POST /api/v1/tasks` + GENERIC 收尾/自校验/建单 |
| R4-6 提交幂等 | ⏸ 移出 R4 | — | 幂等键由前端生成，与前端阶段一起做才可验证 |
| R5-5 AUTO_PASS 基线实测 | ⬜ 未做 | — | 依赖真实 LLM 与 OCR 配额，留待配额稳定时执行 |
| **R9** P4 前置数据源 | ✅ 运行时复验通过（**未提交**） | — | `model_call_log` 台账 + 任务/步骤耗时 + 指标口径文档 |
| R6 / R7 | ✅ 完成并推送 | `610f634` / `c8ae23a` | 结构与契约 / 一致性与文档收口 |
| R8 可选增强 | ⬜ 未开始（R8-1 待商务决策、R8-2/3/4 多为前端项） | — | 见 §5 |

**当前验证基线**：`mvn -o clean install` 19 模块 BUILD SUCCESS；agent-core **201 例**、tool-service **45 例**、
common-model-starter **15 例**、common-code 27 例、tenant-service 25 例、common-mybatisplus-starter 5 例全绿。
数据库 `finaudit` 共 **22 张表**（迁移脚本 `migration-P3.8.sql` 共 18 节，全部幂等可重复执行）。

**八个端到端/专项验收脚本**（`docs/test/`，均带 UTF-8 BOM）：

| 脚本 | 覆盖 | 最近结果 |
|---|---|---|
| `r1-budget-occupancy-e2e.ps1` | R1 预算占用→释放全链路 | 19/19 PASS |
| `budget-occupancy-concurrency.ps1` | R1 并发正确性（直连 MySQL） | 7/7 PASS |
| `bizno-collision-retry.ps1` | R1-8 单号撞库换号重试（占满整秒造撞） | 4/4 PASS |
| `r2-invoice-record-e2e.ps1` | R2 票号入链 + 投影幂等 | 6/6 PASS |
| `r2-audit-timestamp-check.ps1` | R2-10 审计时间戳填充 | 2/2 PASS |
| `r3-invoice-dedup-e2e.ps1` | R3 按票查重 + 票据核验 | 5/5 PASS |
| `r4-review-findings-e2e.ps1` | R4a 结构化问题项 | 19/19 PASS |
| `r5-self-check-e2e.ps1` | R5 自校验落库 + 自纠错重跑 + 轨迹无异常（判据 A/B/C） | **9/9 PASS**（taskId=400683） |
| `r5-amend-rerun-e2e.ps1` | R5-9 顺带修复：驳回 → 修改明细 → 同单重跑全链路 | **19/19 PASS**（taskId=400683） |
| `r6-generic-task-e2e.ps1` | R6-1：GENERIC 走同一套收尾闸口（自校验/轨迹/分支与工单一致，含 autoPass 与 needReview 两场景） | **9/9 PASS ×2**（taskId=400684 / 400685） |

### 跨阶段踩坑清单（按「下次一定还会踩」排序）

工程/环境类：

1. **`docs/test/*.ps1` 必须是 UTF-8 with BOM**。PS 5.1 对无 BOM 的 UTF-8 按 ANSI 解析，
   中文字符串会直接让脚本语法崩掉。⚠️ **`edit` 工具每次写回都会吃掉 BOM**，改完脚本务必复核：
   ```powershell
   $b=[IO.File]::ReadAllBytes($p); '{0:X2} {1:X2} {2:X2}' -f $b[0],$b[1],$b[2]   # 应为 EF BB BF
   ```
   用 `[System.Management.Automation.Language.Parser]::ParseFile(...)` 做语法自检可提前拦住。
2. **不要用 `git commit -m` 传含 `#` 的中文消息**：PowerShell 把 `#` 当注释截断参数。
   统一写消息文件 + `git commit -F <file>`（UTF-8 无 BOM）。
3. **`git checkout <rev> -- .` 只还原文件、不会删除已不存在的文件**。
   重建历史时必须额外 `git rm` 掉该 rev 中已删除的文件，否则内容悄悄多出来（R1/R2 重写时踩到）。
4. **PowerShell 里 `$pid` 是只读内置变量**，别拿它存进程号。

框架/框架约定类：

5. **MyBatis-Plus 的 `ne()` 等条件在 SQL 里执行，mock 不会过滤** —— 单测里若 mock 返回
   「DB 本会排除的行」，会得到假失败。mock 应只返回「DB 本会返回的行」。
6. **mock 的 `insert` 不会回填自增主键**（真实 DB 会）。依赖 `entity.getId()` 的后续逻辑
   （如建关联行）在单测里必须用 `thenAnswer` 模拟回填。
7. **`strictUpdateFill` 是「字段为 null 才填」**，对「实体从库里读出来、字段已有值」的场景是**空转**。
   要覆盖旧值必须用 `setFieldValByName`，并自行校验 `FieldFill` 策略（否则会连未标注的实体一起改）。
8. **`updated_at` 写回会抑制 MySQL 的 `ON UPDATE CURRENT_TIMESTAMP`**：
   `updateById(entity)` 会把实体里读出来的旧 `updated_at` 一并写进 SET（NOT_NULL 策略），
   列一旦被显式赋值，`ON UPDATE` 不再触发。走 `LambdaUpdateWrapper.set(...)` 的更新不受影响 →
   同一张表会呈现「部分行时间戳正确、部分从未刷新」的混合状态。
9. **`@TableField(fill=...)` 必须与 `MetaObjectHandler` 同时就位**，且新增实体的审计字段要记得标注。
10. **手工装配的传输层是「新增字段的静默黑洞」**：`RuleCheckTool`、`DuplicateCheckTool` 等
    用 `m.put("xxx", h.xxx())` 手工把 VO 转成工具输出。给 DTO 加字段时**必须全链路搜一遍装配点**，
    否则字段在 VO 里正确、经过装配层被静默丢弃（不报错、不被 VO 单测覆盖）。
11. **新工具必须三件事齐全**：① `ToolCode` 枚举 → ② `ToolExecutor` 实现 →
    ③ **`tool_registry` 注册**。漏了 ③，`tool-service` 会抛「工具未注册或已禁用」，流水线到该步硬失败。
12. **`@Valid` 嵌套集合不会被级联校验**：`items` 元素上的 JSR303 注解只在配了 `@Valid` 时生效。
    例：明细级 5000 元上限整条链路都是死代码，金额上限实际由 `rule_check` 兜住。
13. **唯一索引决定「一行」，与「需要记录多对多」是同一张表上的冲突**。
    设计投影/快照表时先问：这张表要回答的关系是一对一还是一对多？（R3-7 架构缺陷的根因）
14. **JSON 列禁止用 `wrapper.set(列, 非null值)` 写入，必须走实体更新**：wrapper 参数不带 typeHandler，
    驱动按 binary 字符集发送字符串，MySQL 5.7 报
    `Cannot create a JSON value from a string with CHARACTER SET 'binary'`。
    `wrapper.getSqlSet()` 看起来完全正常，报错只在**驱动绑定参数**时发生——mock 单测永远抓不到。
    实测：实体更新（字段带 `JacksonTypeHandler`）成功，wrapper `set` 必失败（R5-9 的根因）。
    ⚠️ 置 NULL 是例外，`set(col, null)` 实测无问题。
15. **`try/catch` 兜底必须留下落库痕迹**：R5-7 为防单据卡死给自校验加了兜底，结果致命异常被降级成
    「IDE 控制台里的一行日志」——而 agent-core 日志既不落盘、验证方也读不到，于是故障被藏了三轮。
    兜底可以吞异常，但**必须把异常原文写进可查询的持久化字段**（R5-11 的 `result.selfCheckTrace`）。
16. **「真实库最小复现」是最快的定位手段**：拿生产同一套 Mapper/实体，手工装配 `SqlSessionFactory`，
    对真实库跑一遍可疑的 DB 操作序列，能一次区分「wrapper 构造问题 / 参数绑定问题 / 表结构问题」。
    比在应用里加日志、重启、复跑快一个数量级（本仓可参考 R5-9 的复现程序思路，
    成品见 `docs/test/repro/JsonColumnWriteRepro.java`）。
17. **校验脚本里的 `Api()` 必须区分「R 结构响应」与「非 R 响应」**：把 `Invoke-WebRequest` 的异常体
    直接 `ConvertFrom-Json` 时，404/403 的空体或 Spring 默认错误体会被解析成**空对象**，
    于是 `code`/`message` 全空、被判成「业务失败」并可能降级为 SKIP —— **脚本看似全绿，其实那段压根没测到**。
    实测踩过：端点前缀写成 `/api/v1/audit-tickets`（实为 `/api/v1/audit/tickets`）返回 404，
    amend 脚本 16/16 PASS 却从未执行驳回。修法：非 R 响应统一返回
    `{ code = -1; message = "HTTP <status> <body>" }`。
    **推论**：验收脚本里的 `SKIP` 必须打印原始响应，禁止无痕降级。
18. **脚本必须显式区分「前置条件不满足」与「产品缺陷」**（第 17 条的一般化）。两个实例：
    ① `r2-audit-timestamp-check.ps1` 要求两次提交**用同一张样本图**（否则第二次 OCR 到别的发票，
    压根没走「同一票累加 seen_count」这条被验证的路径），但脚本内部各自随机取样本，
    于是「换了一张票」被报成产品级 FAIL「updated_at 未跳变 ⇒ 填充未生效」；
    ② `r5-amend-rerun-e2e.ps1` 驳回失败会被当成 SKIP 静默带过（见第 17 条）。
    **修法**：把「本次运行是否真的走到被验证路径」写成显式前置断言，不满足时以
    `SKIP（前置条件不满足，非产品缺陷）` 结束并说明如何正确重跑。
    脚本随机取样本 / 随机取单据的地方，都要问一句：这个随机性会不会让断言失去意义？

19. **`finaudit-schema.sql` 是"全量重建"脚本，内含 `USE finaudit` + `DROP TABLE`**（P3.8 R9 实测踩过，
    一次误操作把本地库清空、且本机 `log_bin=OFF` 无从恢复）：
    ① 命令行 `-D 其他库` **保护不了数据**（脚本内的 `USE` 会覆盖它）；
    ② 只想"验证全量脚本能否在空库跑通"时，必须**复制脚本并删掉 `USE`/`DROP` 段**，不要直接对生产/开发库执行；
    ③ **新增表必须同步加进该脚本的 DROP 列表**——漏加会让重跑在中间某张表报 `Table 'xxx' already exists` 并中断，
    留下「结构新、种子空」的半成品库（`sys_user` 为空 → 无法登录）。R9 加 `model_call_log` 时漏了，已修复，
    并在脚本头部与 `docs/database/README.md`、`docs/deploy/README.md` 三处加了同款警示。
    **纪律**：任何"看起来幂等"的重建脚本，执行前都要先问「它会 DROP 什么、指向哪个库、有没有备份」。
    **配套产物**：`docs/deploy/db-backup.ps1`（一条命令备份 + 打印前后关键表行数，输出到已 gitignore 的 `backups/`）；
    `docs/database/README.md` §2.2 记录"安全试验 SQL 脚本"的正确姿势（复制脚本改 `USE` 行，且必须显式按 UTF-8 读写）。

验证方法类：

17. **「某值应被更新」的断言必须对比前后跳变**，绝不能与 0 或某个绝对值比较 ——
    否则历史遗留偏差会让「修复无效」也报 PASS（R2-10 首版就假通过了）。
18. **删掉能 PR 的符号必然留下死代码**：R0 拆 `/internal` 漏删 `AuditDataController`、
    R3 删 `findBudgetRow` 留下失效 `@link`。删改端点/方法后必须全仓 grep 旧名。
19. **端到端验收不可省**：R3-7（一票多单归属）、R4-7（装配层丢字段）两个缺陷
    **单测全绿、只有端到端才暴露**。
20. **验收前先确认目标进程还活着**：R1-8 首次造撞其实成功了，但目标进程随后重启导致日志丢失，
    被误判为「没撞上」，白折腾数轮。
21. **「落库成功」的表象可能来自另一个写入路径**：R5-9 里 `self_check_result` 有值并非因为
    `applySelfCheckResult` 成功，而是后续 `markApprovalPending` 用实体更新把内存字段顺带写了进去。
    判断某次写入是否成功，要看**该语句自己**的结果，而不是最终列里有没有值。
22. **状态机断言必须写成「等价关系」，不能写成「某状态下必然如何」**（P3.8 R8-3 复验实测）：
    首版进度脚本断言「`status=RUNNING` ⇒ `currentStepName` 非空、`estimatedRemainingMs > 0`」，
    结果把「8 步全 SUCCESS 但尚未迁终态」的**收尾判定窗口**（真实存在约 1~2 秒）
    报成 3 条 FAIL。正确写法是把断言与**可查的驱动量**绑定：
    「未完成步骤数 > 0 ⇔ 有当前步骤 ⇔ 剩余 > 0」、「未完成步骤数 = 0 ⇔ `message` = 收尾文案」。
    **排查口诀**：一条断言挂掉时，先问「这个状态组合在真实运行时是否合法」，再问「产品是否有缺陷」——
    否则会把正常状态当成 bug 去"修"，甚至改坏正确的实现。
22. **HTTP 响应的中文必须按 UTF-8 显式解码后再断言**（P3.8 R8-3 实测）：本仓 JSON 响应头是
    `Content-Type: application/json`（**不带 charset**），按 HTTP 规范无 charset 等价于 ISO-8859-1，
    于是 Windows PowerShell 5.1 的 `Invoke-WebRequest` 把中文解成 `å·²å®Œæˆ` 这类乱码 ——
    **凡按字面比对中文的断言都会误判**（R8-3 首轮就出现假 FAIL「终态文案可读」）。
    产品侧无需改动（浏览器/Jackson/Feign 对 `application/json` 默认按 UTF-8），修法在脚本：
    ```powershell
    [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())   # 成功路径
    [System.Text.Encoding]::UTF8.GetString((Read-AllBytes $resp.GetResponseStream()))  # 异常路径
    ```
    ⚠️ **MySQL 那条路径不受影响**（`mysql.exe` 输出走 `--default-character-set=utf8mb4`），
    故 r4/r5 等脚本里来自库的中文断言一直是好的 —— 排查时先问「这个中文是从 HTTP 来的还是从库来的」。
    另：控制台中文乱码是**另一个**问题（`[Console]::OutputEncoding`），两者不要混为一谈。
23. **「展示型指标回退」必须给出机制字段，不能靠改回退为单调来"修"**（P3.8 R8-3 实测）：
    `progressPct` 实测从 87.5% 回退到 62.5%，根因是 R5 自校验纠错**重跑风险步骤**、把步骤状态从
    `SUCCESS` 复位（同一步骤行，不是 replan 新增行，`correction_count + 1` 可查）。
    端点的正确做法是**如实返回 + 透出 `correctionCount`**，让前端能显示「正在重新核验」；
    若为了进度条好看而谎报单调递增，等于把「工作被重做」这件事从系统里抹掉。
    顺带：验证脚本的单调性断言要写成**机制感知**的（回退 + `correctionCount ≥ 1` → PASS；
    回退 + `correctionCount = 0` → FAIL），否则脚本会把一个正常现象报成缺陷，逼人去"修"对的东西。


---

### R0 · 主链路止血 —— ✅ 已完成（提交 `3114b60`，双仓已推送）

> 📌 **历史说明**：本阶段实施期间 shell 曾被沙箱拒绝（`SetNamedSecurityInfoW failed (Win32 5): grantWrite(...)`，
> 尝试 7 次 / 4 种调用方式均失败），故当时所有改动仅经静态审查，标注为"未经编译验证"。
> **该限制已解除**：后续在 JDK 21 下完成 `mvn clean install`（19 模块 BUILD SUCCESS）、
> 全部单测与端到端验收，并已按「一阶段一提交」推送到双仓。以下内容保留作过程记录。

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
- [x] ~~按 AGENTS.md §6 提交 + 双仓推送（R0 为一个阶段）~~ **已完成**：提交 `3114b60`，双仓已推送
- [x] ~~进入 R1（预算真实占用与释放）~~ **已完成**：见下文 R1 段落
- [x] R1 / R2 / R3 / R4a 均已完成并推送
- [x] R5 代码与单测完成 → **已提交并双仓推送**（`b59a265`）
- [x] ~~**下一步：提交 R5**~~ **已完成**（一阶段一提交，双仓推送）
- [x] ~~**重启 agent-core 联调 R5**~~ **已完成**：自校验命中 → 重跑风控步骤 → `correction_count`/`self_check_result` 落库（`r5-self-check-e2e.ps1` 9/9、`r5-amend-rerun-e2e.ps1` 19/19）
- [ ] R5-5 AUTO_PASS 基线实测（依赖真实 LLM 与 OCR 配额，见 R5 待办）
- [x] ~~R6 / R7 中需补的历史欠账：R2-10 审计时间戳填充对其余实体仍未生效~~ → **已于 R9 关闭**（15 个实体补齐 `@TableField(fill = FieldFill.INSERT_UPDATE)`，见 §11 R9 记录）
      （`agent_task` / `audit_ticket` / `budget_occupancy` 等仍走 `updateById` 且未标注 `FieldFill`，
      `updated_at` 依然从不刷新）——需逐个实体评估后补标注

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

---

### R1 · 预算真实占用与释放 —— ✅ 后端完成（**待用户迁移 DB + 联调**）

> 业务地基（B-1 / B-2）：`budget.used_amount` 此前**全仓无写入点**，只做只读预检，
> 同一部门同月多笔报销全部报"预算充足"、系统一次都不拦。

**实现要点**

| 项 | 落点 | 要点 |
|---|---|---|
| 建表 | `migration-P3.8.sql` + `finaudit-schema.sql` + `tables.md` | `budget_occupancy`：一单一记录 `uk(tenant_id, reimb_id)`；状态 OCCUPIED/RELEASED + 占用/释放次数 |
| 原子 SQL | `BudgetMapper.xml` | `occupy`：`used_amount+amount<=total_budget` **写在 WHERE 里**（行锁串行化）；`release`：`GREATEST(...,0)` 防负数；`findBudgetRow`：只读试算 |
| 服务 | 新增 `BudgetOccupancyService` | 唯一写入口；幂等（重复占用不二次累加、重复释放不二次扣减）；未配置预算跳过仅告警；`reconciledNet` 提供占用-释放配平对账 |
| 占用接入 | `AgentOrchestrator.finalizeSuccess`（AUTO_PASS 前）、`AuditTicketService.approve` | **刻意置于 markSuccess 之前**：若先标记成功、占用又失败，事务回滚会把已完成任务一起回滚 |
| 释放接入 | `approve` 之外的各终态：`reject`/`terminate`（防御式幂等）、**`withdraw-agree`（真正需要释放——此前已 APPROVED 已占用）** | 同意撤销若不释放，预算会被作废单据永久吃掉，比不占用更糟 |

**两个设计决策（重要）**

1. **用「只读试算 + 原子占用」两步，而不是「占用后 catch 异常转人工」**：
   在事务内捕获 `BizException` 后继续写库（如 `enterApproval` 建工单）是危险的——
   MySQL 尚可，**PostgreSQL 下事务已被标记 aborted（25P02）**，后续语句全失败，
   且依赖 Spring 对回滚异常的判定过脆。故把"预算不足"变成一次不写库的预检（`canOccupy`），
   AUTO_PASS 前判定不通过则连同原因一并转 NEED_REVIEW。
   预检存在 TOCTOU 窗口，最终正确性仍由原子 UPDATE 保证（并发抢额度时抛错回滚）。
2. **未配置预算不阻断**（保持历史宽松语义，仅告警）：存量种子只覆盖 4 个部门 1 个周期，
   强制阻断会让大量单据直接失败。

**验证（均已实测）**

| 项 | 方式 | 结果 |
|---|---|---|
| 单测 | `BudgetOccupancyServiceTest` | **15 例全绿**（幂等/未配置/不足拦截/重跑再占用/配平/周期推导） |
| 回归 | `mvn test`（全后端） | agent-core **86 例**全绿（原 66 + R1 新增 15 + 单号重试新增 5）；其余模块全绿 |
| **并发正确性** | `docs/test/budget-occupancy-concurrency.ps1` 直连真实 MySQL 5.7.10 | **7/7 PASS**：场景1（20 并发×600/预算10000）成功 16 笔、`used_amount=9600` 与成功笔数×600 一致（无丢失更新）；**场景2（核心，20 并发×6000/预算10000）仅 1 笔成功、`used_amount=6000` 未超总额**；场景3 `GREATEST` 防负数生效 |
| 迁移脚本 | 实际执行 `migration-P3.8.sql` | 表已建出、结构与预期一致；沙箱数据（tenant_id=9999）已清理 |
| 全量 schema | 导入一次性库 `finaudit_schema_check` | exit 0，19 张表齐全（含 `budget_occupancy`） |

> 场景2 是关键判据：若用"先查再算再写"，20 个线程会读到同一个 `used_amount=0` 而全部判定充足，
> 最终 `used_amount=120000` 超支 12 倍。

**待办**

- [ ] **用户执行迁移**：`mysql -uroot -p < docs/database/migration-P3.8.sql`
- [ ] **重启 agent-core-service**（新表 + 新服务生效）
- [ ] 联调验收：approve 后 `used_amount` 增加 → `withdraw-agree` 后回落且不为负；同一预算并发两笔超额单据，第二笔进 NEED_REVIEW/被拦
- [ ] **R1-7 前端展示延后**（按 AGENTS.md §7 后端先行）：报销单详情/预算页展示「本单占用 / 部门已用 / 剩余」

---

### R1-8（联调期发现的偶发故障）：单号熵不足导致同秒提交撞库

> 现象：R1 端到端脚本连跑时，偶发第二笔提交返回 400「数据唯一约束冲突，请检查后重试」。
> 起初怀疑并发写坏数据，实际是**单号生成器随机位不够**。

**根因**

`ExpenseReimbursement.generateReimbNo()` 与 `AgentTask.generateTaskNo()` 都是
`前缀 + yyyyMMddHHmmss(秒级) + ThreadLocalRandom.nextInt(10000)` 的 4 位随机段，
分别对应唯一索引 `uk_reimb_no` / `uk_task_no`。同一秒内提交 N 笔时，碰撞概率约
`C(N,2)/10000` —— 单笔看是万分之一，但脚本连跑 / 生产高峰期必然命中。
碰撞后抛 `DuplicateKeyException`，被 `GlobalExceptionHandler` 兜成用户可见的
400「数据唯一约束冲突」，用户只看到一个莫名其妙的失败。

> 复现难度也印证了这一点：手工在**同一秒**连续提交两笔（`R202609120057 011854` /
> `R202609120057 017607`）**均成功**，无法按需复现——它是概率事件而非确定性缺陷。
> 全表唯一索引仅 17 个，逐项排除后只剩 `uk_reimb_no` / `uk_task_no`
> （`expense_attachment` 无唯一索引），定位收敛。

**修复**（`BizNoInserter`，新增）

| 项 | 落点 | 要点 |
|---|---|---|
| 换号重试 | 新增 `support/BizNoInserter.java` | 捕获 `DuplicateKeyException` → 重新生成单号 → 重试，最多 3 次 |
| 清主键 | 同上，`idClearer` 回调 | 首轮 INSERT 失败时自增主键可能已被回填，**重试前必须置 null**，否则会按主键写入冲突行 |
| 只吞撞号 | 同上 | 其余 `RuntimeException`（字段超长、非空约束等）原样上抛，不掩盖真实故障；连续 3 次仍撞才抛可读的 `BizException` |
| 接入 | `ReimbursementService.submit`、`AgentTaskService.createTask` | 仅这两处单号落库点 |
| 可见性 | 两个 `generateXxxNo()` 改 `public` | 跨包方法引用需 public；均为纯函数，无副作用 |

> 未改成「提高随机位数 / 雪花 ID」：单号是**对外可读**的业务编号，前缀+时间的格式
> 有查询与沟通价值；重试方案不改变格式与既有数据，且对未来任何唯一索引变更都免疫。

**验证（AI 自测，非用户验收）**

| 项 | 方式 | 结果 |
|---|---|---|
| 单测 | `BizNoInserterTest` | **5/5 通过**：首次成功不重试 / 撞号换号成功 / 重试前清主键 / 连续 3 次撞号抛 `BizException` / 非撞号异常原样上抛 |
| 回归 | `mvn -o clean install`（19 模块） | **BUILD SUCCESS**；agent-core 86 例全绿（81 + 5），tool 11 / tenant 20 / file 3 全绿 |
| **端到端造撞** | `docs/test/bizno-collision-retry.ps1` | **4/4 PASS**（见下） |
| **服务端日志实证** | agent-core 控制台（用户提取） | 见下：`第 2 次/第 3 次碰撞 → 放弃重试` 三行，最坏分支与对外语义均已实测 |

**端到端造撞（务实难点：怎么让万分之一必然发生）**

自然撞号概率万分之一，靠连跑脚本验证不到。本脚本用「占满整秒」把概率变成必然：

1. 等到下一整秒瞬间 → 立即向 `agent_task` 插入该秒的 **10000 个尾号**（0000-9999）→ 该秒内无论随机数抽到什么，`uk_reimb_no` / `uk_task_no` 必撞；
2. 请求发出后由**后台占有线程**连续占满后续每秒（覆盖请求后 1~9 秒）。

> 中途两次方案失败也记在这里，避免后人重走：
> - **只占尾号 0-9 是错的**：撞中概率仍只有 `10/10000`，等于换个姿势继续赌千分之一；
> - **"先占号再提交"也是错的**：单号并非在提交那一刻生成，`submit()` 在生成号段前还要走
>   「文件服务 Feign 校验附件 → 部门校验 → 金额重算」等远程调用，**实测号段生成时刻比请求发出晚 2~4 秒**，
>   先前占好的那一秒早已过去。必须用后台线程在请求进行中持续占号。

实测结果：`R202609120108336852` / `T202609120108331737`，秒段 `20260912010833` 正是被占满的那一秒。
反证同样有力——`uk_task_no` 在 `agent_task` 的唯一索引上，该秒 0-9999 全部被占，
插入成功的 `14642` **只能是换号得来**（探针占用 id 6400~14641）。

**服务端日志实证（用户从 agent-core 控制台提取，2026-09-12 01:14:09）**

```
WARN  c.f.agentcore.support.BizNoInserter : 任务号碰撞，换号重试（第 2 次）：任务号=T202609120114086523
WARN  c.f.agentcore.support.BizNoInserter : 任务号碰撞，换号重试（第 3 次）：任务号=T202609120114091757
ERROR c.f.agentcore.support.BizNoInserter : 任务号连续 3 次碰撞，放弃重试：任务号=T202609120114095370
```

这段日志把三条结论一次性钉死：

1. **重试确实重新生成了单号**：`…6523` → `…1757` → `…5370` 三个号互不相同，
   证明不是拿同一个号反复重插（那才是无意义重试）。
2. **三次尝试全落在 `01:14:09` 同一秒内**（`.151` / `.157` / `.163`，间隔仅 6ms）：
   因为该秒 10000 个尾号已被占满，`now()` 不变 ⇒ 换号后仍在同一秒 ⇒ 必然继续撞。
   这也顺带证明了"连续 3 次撞号"这一最坏分支可被真实触发，而非纸面推演。
3. **耗尽重试后对外返回可读提示**：对应的正是脚本第 `[2.1]` 次提交返回
   `code=400`，而非暴露唯一索引细节的「数据唯一约束冲突」。

> 老代码在此处**不可能**产生任何 `BizNoInserter` 日志：它只做单次
> `taskMapper.insert(task)`，撞了就抛 `DuplicateKeyException` 被全局处理器兜住。
> 故"存在这些日志"本身就是"新代码在跑"的充分证据。

**同轮的成功分支**（`01:15:40`，脚本 **4/4 PASS**）：号段生成于
`20260912011542`（该秒已确认占满 10000/10000），换号后成功落库
`R202609120115429457` / `T202609120115427480`，对外 `code=0`——
即"撞号但用户无感"的预期行为。


> 另：产品侧 `BudgetOccupancyService.reconciledNet` 的对账公式同期发现同一处错误
> （见 R1-9），本脚本的对账断言口径已一并按正确公式修正。

> **日志可验证性（记录在案，未实施）**：全仓无 logback 配置，日志只进控制台，
> IDE 控制台与自动化脚本所在 shell 相互隔离，脚本读不到日志，只能人工确认。
> 曾尝试为 agent-core 增加 `logback-spring.xml` 落盘，**按用户要求撤回**
> （明确不要落盘实体日志文件）。故本脚本的日志判据仍需人工在控制台核对。


---

### R1-9（端到端脚本牵出的产品缺陷）：配平对账公式重复扣减释放额

> 现象：R1 端到端脚本报「本轮记账净额 == budget.used_amount」失败，算出 `-6863.00`，
> 而 DB 里 `used_amount = 142.00` 完全正确。起初判为脚本断言写错，**实为产品代码同款错误**。

**根因**

`budget_occupancy` 是「**一行一单 + 状态流转**」：`OCCUPIED ⇄ RELEASED` 是**同一行**的状态迁移
（`applyRelease()` / `applyReoccupy()`），转 `RELEASED` 时 `budget.release` 已把该行金额
从 `used_amount` 扣回。所以 RELEASED 行本身就表示「不再计入」，**整条跳过**即可。

但 `reconciledNet` 用了 `Σ(OCCUPIED) − Σ(RELEASED)`，等于把已释放金额**再扣一次**。
实测一轮「占 142 → 占 7005 → 撤销释放 7005」：`142 − 7005 = −6863`，而真实 `used_amount = 142`。

**为什么单测没拦住**：原单测是「占用 600 + 释放 600 → 期望 0」，这个期望值恰好让错误公式
算出"看着很对"的 0；而真实值应是 600。断言与实现同源于同一个错误理解，形成闭环。

**修复**

| 项 | 落点 | 要点 |
|---|---|---|
| 对账口径 | `BudgetOccupancyService.reconciledNet` | 改为只累加 `status='OCCUPIED'` 的行，RELEASED 整条跳过 |
| 语义注释 | `BudgetOccupancy` 类注释 | 配平公式更正为 `Σ(amount WHERE OCCUPIED) == used_amount`，并注明"再按占用−释放计算会扣减两次" |
| 单测 | `BudgetOccupancyServiceTest` | 原 `reconciledNetSubtractsReleasedFromOccupied`（期望 0）改为 `reconciledNetCountsOnlyOccupiedRows`（期望 600）；新增 `reconciledNetZeroWhenAllReleased` |
| 脚本口径 | `r1-budget-occupancy-e2e.ps1` | 两处对账断言改为 `SUM(amount) WHERE status='OCCUPIED'` |

**验证**

| 项 | 方式 | 结果 |
|---|---|---|
| 单测 | `BudgetOccupancyServiceTest` | **16/16 通过**（原 15 + 新增 1） |
| 端到端 | `docs/test/r1-budget-occupancy-e2e.ps1` | **19/19 全 PASS**（此前 17 PASS / 2 FAIL，两处 FAIL 即本缺陷导致的假失败） |

> 影响面：`reconciledNet` 仅供运维/演练核验，未暴露为 HTTP 端点，**不影响业务主链路**；
> 但它是"预算被永久吃掉"这一 R1 主要风险的核验手段，公式错了等于没有监控，必须修。

---

### R2 · 发票标识符入链 —— ✅ 后端完成（**待用户执行迁移 + 联调**）

> 业务走查 B-3。发票代码/号码在 OCR 链路里**早已解析出来却被丢弃**：
> `BaiduOcrService.toVatResult` 把 `InvoiceCode`/`InvoiceNum` 装进了 `VatInvoiceOcr`，
> 但 `OcrExtractTool.normalize` 的输出里没有这两个字段，且 `expense_attachment.ocr_result`
> 是 JSON —— MySQL 5.7 无法对 JSON 内部字段建索引，票号也没有可索引的落点。
> 后果：`duplicate_check` 只能靠「同申请人 + 金额完全相等 + 日期±30天」的启发式，
> 把正常单据误判为重复（R1 联调时已实测复现该误报）。

**实现要点**

| 序 | 落点 | 要点 |
|---|---|---|
| R2-1 | `OcrExtractTool.normalize` | 补 `invoiceCode`/`invoiceNum`：vat 专用结果优先，非增值税模板回退原始中英文字段；统一 `trim`，空白归一为 null（唯一键要求确定性取值）。另补 `ocrDate`（`yyyy-MM-dd`）——`date` 是**展示用原样串**（财会版实际返回中文「2026年08月01日」），`DATE` 列无法直接入库，必须显式解析（vat 的 `LocalDate` → ISO → 中文格式三种来源） |
| R2-2 | `invoice_record` 表（迁移第 3 节 + `finaudit-schema.sql` + `tables.md`） | 票号投影为普通列：`uk_invoice(tenant_id, invoice_code, invoice_num, deleted)` + `idx_invoice(tenant_id, invoice_code, invoice_num)` 供 R3 硬命中 |
| R2-3 | 新增 `InvoiceRecordService`（唯一写入口）+ `InvoiceRecord` 实体、`InvoiceRecordMapper` | `AttachmentService.updateOcrResult` 内收敛调用，OCR 回写事务内 UPSERT 语义：同票重复识别**累加 `seen_count` 不新增行**；并发撞唯一键转「读既有行 + 累加」，不让调用方失败 |
| R2-4 | 迁移第 4 节回填 + 第 5 节报告 | 从 `ocr_result` JSON 抽票号投影存量数据；`INSERT IGNORE` 可重复执行；报告输出命中数 |

**三个设计决策（重要）**

1. **缺失票号归一为「空串」而非 NULL**：MySQL 唯一索引**不约束 NULL**，落 NULL 会让同一张票
   被无限次重复投影、唯一键形同虚设。`invoice_code` 列因此是 `NOT NULL DEFAULT ''`。
2. **无票号的票据整体跳过投影**，不用空串占位：火车票/打车票本就没有票号，
   若统一占位落库，同租户所有无票号票据会挤在同一条唯一键上互相冲突。
3. **`InvoiceRecordService` 不注入 `AttachmentService`**：`reimbId` 由调用方（`AttachmentService.updateOcrResult`，
   手里已有附件实体）通过入参传入。反向注入会形成 `AttachmentService ⇄ InvoiceRecordService`
   构造器循环依赖，Spring 默认禁止循环引用 → **启动直接失败**（本阶段实际踩到并已断开）。

**验证（AI 自测，非用户验收）**

| 项 | 方式 | 结果 |
|---|---|---|
| 单测（归一化） | `OcrExtractToolNormalizeTest` | **9/9 通过**：VAT 含票号、电子发票无代码、非 VAT 模板回退中文 key、行程票无票号为 null、空白清洗、vat 优先、中文日期解析、不可解析日期为 null |
| 单测（投影） | `InvoiceRecordServiceTest` | **13/13 通过**：票号落普通列、无票号跳过、空白跳过、同票累加不新增、并发撞键转累加、缺失代码归一空串、trim 确定性、空集合不生成 `IN ()` 等 |
| 回归 | `mvn -o clean install`（19 模块） | **BUILD SUCCESS**；agent-core **100 例**（87 + 13）、tool-service **20 例**（11 + 9）、file-service 3 例全绿 |
| schema DDL | 导入一次性库 | 退出码 0，**20 张表**（19 + `invoice_record`）；`uk_invoice` / `idx_invoice` 均按预期建出（已核对 `SHOW INDEX`） |
| **唯一键语义** | 临时库实测 | ① 重复票插入被拦：`Duplicate entry '1-C1-N1-0' for key 'uk_invoice'`；② 逻辑删除后可重插同票（`deleted` 位于唯一键的意义），未删行数恒为 1；③ 不同租户同票号互不影响 |
| **回填 SQL** | 临时库实测（两种存量日期形态） | 中文存量 `"date":"2026年08月01日"` → `inv_date=2026-08-01`；新数据 `ocrDate` ISO → `2026-08-05`；不可解析 `"8月6日"` → NULL；无票号/OCR FAILED/软删行被正确排除；同票两行附件去重为 1 行；重复执行行数不变（幂等） |

> **回填里的一处真 bug（已修）**：初版取 `$.date` 并用 `%Y-%m-%d` 解析。
> 但 `$.date` 是展示用原样串，财会版返回中文格式；而本回填处理的**正是 R2 之前入库的存量数据**，
> 那批数据没有 `ocrDate`、只有中文 `date` —— 只试 ISO 会让存量单据的开票日期**全部回填为 NULL**。
> 已改为 `COALESCE(ISO, 中文格式变体)`，并在临时库用中文存量数据实测通过。

**端到端验收（已实测，用户重启后）**

迁移由 AI 在真实 `finaudit` 库执行（用户授权），结果：

| 项 | 结果 |
|---|---|
| 迁移退出码 | 0；表总数 19 → **20**；`budget_occupancy` 2 行未受影响 |
| 幂等 | 重复执行退出码 0，表数不变 |
| `invoice_record` | 0 行（回填命中 0，**符合预期**） |

> **一个重要发现**：执行前查明库内 15 条 OCR SUCCESS 的 `ocr_result` JSON 字段只有
> `date/taxNo/amount/merchant/receiptType` —— **完全没有票号字段**。这是 B-3 缺陷在生产数据上的直接证据：
> 票号在 `BaiduOcrService` 里解析出来后，被 `normalize` 丢掉、从未落库，故无历史数据可回填。

重启 agent-core（`01:34:35`）与 tool-service（`01:34:44`）后，`docs/test/r2-invoice-record-e2e.ps1`：

| 判据 | 结果 |
|---|---|
| ① R2-1 归一化 | **PASS**：新 `ocr_result` JSON 含 `invoiceCode`/`invoiceNum`/`ocrDate` 三个新字段（老代码绝不产生）。实测识别出发票 `invoiceCode=044002311111`、`invoiceNum=07632553`、`ocrDate=2023-06-19` |
| ② R2-3 投影 | **PASS**：`invoice_record` 落行 `044002311111 / 07632553 / reimb_id=43 / seen_count=1` |
| 幂等 | **PASS**：用同一张样张重复提交（不同 `file_record`），同一张票**始终只有 1 行**，`seen_count` 随识别次数累加（1→2→3→4→5），`reimb_id`/`attachment_id` 刷新为最新来源 |

**附带修复：审计时间戳失真的框架级缺陷（R2-10）**

联调中发现 `invoice_record.updated_at` 恒等于 `created_at`。严格前后对比（同一行连续改写）：

```
BEFORE: seen_count=4 attachment_id=45 reimb_id=47 updated_at=01:37:22
AFTER : seen_count=5 attachment_id=46 reimb_id=48 updated_at=01:37:22   ← 三字段都变了，时间戳不动
```

**根因**：DDL 里 `updated_at` 是 `ON UPDATE CURRENT_TIMESTAMP`，但 MyBatis-Plus 的
`updateById(entity)` 默认更新策略为 **NOT_NULL**，会把实体里**从库里读出的旧 `updated_at`**
一并写进 SET 子句；列一旦被**显式赋值**，MySQL 的 `ON UPDATE` 就不再触发。
对照实验证实：SET 里去掉 `updated_at` → 时间戳立刻刷新；显式带旧值 → 时间戳冻结。

**影响面**（实测各表 `updated_at <> created_at` 的行数）：
`agent_task` 0/45、`audit_ticket` 0/39、`budget_occupancy` 0/2 —— **从未刷新过**；
这些表全部走 `updateById`。而走 `LambdaUpdateWrapper.set(...)` 的更新（SET 里不含 `updated_at`）
不受影响，故 `agent_task_step` 14/327、`expense_reimbursement` 15/45 呈现混合状态。

**修复**：`common-mybatisplus-starter` 新增 `AuditTimestampMetaObjectHandler`，实体字段标注
`@TableField(fill = FieldFill.INSERT_UPDATE)`。R2 先作用于 `InvoiceRecord`；
其余实体的标注属于独立重构项（见 R6/R7），避免本阶段一次改动面过宽。

> 审计时间戳异常直接污染财务系统的追溯性，且该框架缺陷会随新实体不断复制，故在本阶段一并修掉。

**⚠️ 修复过程中踩的三个坑（都记下来，避免重走）**

1. **`strictUpdateFill` 是空转的**。其语义为「字段为 null 才填」，而 `updateById(entity)`
   传进来的实体恰恰**是从库里读出来的**——`updatedAt` 早有旧值，填充被**静默跳过**，
   旧时间戳照样进 SET，缺陷原样保留。首版就是这么写的，**运行时验证失败才发现**；
   单测直接复现：`UPDATE 必须用当前时间覆盖旧 updatedAt，实际=2026-01-01T00:00`。
   已改用 `setFieldValByName` 强制覆盖。
2. **`setFieldValByName` 会越过 fill 策略**，连**未标注 `fill` 的实体也一起改**，
   违反「未标注实体零影响」的承诺（被单测 `updateFillLeavesUnannotatedEntityUntouched` 捕获）。
   故补显式策略守卫：从 `TableInfo` 读 `@TableField(fill=...)` 的解析结果，
   只放行 `UPDATE` / `INSERT_UPDATE`。
3. **测试放错模块**：该测试最初放在 `agent-core-service` 下，而该模块**并不依赖**
   `common-mybatisplus-starter`——只因"能编译过"就误以为放对了（实为 fat jar 传递才编过）。
   已移到 `common-mybatisplus-starter` 自身测试，并用本地夹具实体避免反向依赖。

**运行时验证（用户重启后实测通过）**

```
[1] 第一次提交后: updated_at = 01:49:10  seen_count = 8
[2] 第二次提交后: updated_at = 01:49:15  seen_count = 9
  [PASS] seen_count 已累加（8 → 9），确认走了 updateById 路径
  [PASS] updated_at 在两次提交之间跳变：01:49:10 → 01:49:15（+5 秒）⇒ 自动填充生效
```

> **断言口径也必须跟着改（重要教训）**：首版脚本只断言 `updated_at <> created_at`（即 `diff > 0`），
> 结果修复**无效**时它照样"通过"——因为那个 `diff` 是几十分钟前手工实验留下的偏差（128 秒），
> 与本轮写入无关。已改为比对**两次提交之间的跳变**（`tsAfter > tsBefore`）。
> **凡"某值应被更新"的断言，必须对比前后跳变，绝不能与 0 或某个绝对值比较。**

**待办**

- [x] **迁移已由 AI 执行**（用户授权）：`source docs/database/migration-P3.8.sql`，退出码 0、幂等
- [x] **agent-core 与 tool-service 已重启**（`01:34:35` / `01:34:44`）
- [x] 联调验收：判据 ①② 均 PASS，幂等累加 PASS（见上）
- [x] **`updated_at` 自动填充运行时复验通过**（重启后实测 `01:49:10 → 01:49:15` 跳变）
- [ ] **R3 依赖本阶段产出**：`queryDuplicates` 改按 `(invoice_code, invoice_num)` 硬命中
- [x] R6/R7：为其余实体补 `@TableField(fill=...)` 标注（R2-10 缺陷对它们仍未修复）→ **已于 R9 关闭**（15 个实体，详见 §11 R9）

---

### R5 · 语义自校验与自主纠错 —— ✅ 后端完成（**待重启联调**）

> A-1：需求标准③「自主任务拆解 / 分步执行 / 工具联动 / **自主纠错**」中，自主纠错此前**实质未实现** ——
> 只有 TOOL 步骤传输层重试 3 次（即时重发、无退避），没有任何**语义层**的
> 「结果合法性与交叉一致性校验 → 异常自动重执行」。

**实现要点**

| 序 | 落点 | 要点 |
|---|---|---|
| R5-1 | 新增 `domain/SelfCheckResult` + `service/SelfConsistencyChecker` | 5 条确定性交叉一致性断言；命中即返回不通过，并给出矛盾/幻觉清单 |
| R5-2 | 自校验接入 `AgentOrchestrator.finalizeSuccess` 的**收尾闸口** | 通过才允许 AUTO_PASS；**设计偏差见下** |
| R5-3 | 命中矛盾 → `resetForSelfCorrection` 重置风控语义步骤（含其后步骤）→ `continueTask` 重跑；上限 1 次，超限转人工 | 矛盾提示经 `updateInputParams` 注入风控步骤入参 |
| R5-4 | `agent_task` 增 `correction_count` / `self_check_result`（迁移第 11 节）+ `AgentTask` / `TaskVO` 透出 | `correctionCount` 用 SQL 自增（`IFNULL(...)+1`）避免并发丢失 |

**⚠️ 设计偏差（R5-2）：自校验不做成流水线步骤，而是收尾闸口**

原计划是在 `RuleBasedFlowEngine` 里 LLM 风控之后、结论汇总之前插一个 `self_check` 步骤。
实施时发现**该位置拿不到断言 ①③ 需要的数据**：这两条断言要对比「汇总结论」，
而汇总结论由排在最后的 LLM 汇总步骤产出——校验插在它之前，结论还不存在，断言只能空转。

故改为在 `finalizeSuccess`（收尾判定 AUTO_PASS 之前）执行校验。此时风控评估与汇总结论都已落库，
5 条断言才都有真实数据可比。**代价**：`RuleBasedFlowEngine` 步骤序列不变（仍 8 步），
前端步骤列表里不会出现 `self_check` 这一行；自校验明细改由 `agent_task.self_check_result` 透出。

**5 条断言**

| 序 | 断言 | 数据来源 |
|---|---|---|
| ① | `amount_verify.match=false` 但汇总结论 APPROVE | R0 起的金额核验 + LLM 汇总 |
| ② | `rule_check.overLimit=true` 但风险等级 LOW | R4 结构化规则命中 + LLM 风控 |
| ③ | `invoice_match.match=false` 但汇总结论 APPROVE | **R3 真实产出**（票据-明细交叉核验） |
| ④ | `duplicate_check` 硬命中但风控置信度 ≥ 0.9 | **R3 真实产出**（按票号硬命中） |
| ⑤ | LLM 输出引用了前序步骤中不存在的发票号（幻觉） | R2 落库的票号 + LLM 自由文本 |

**为什么这算「自主纠错」而不是又一层规则**：它**消费 LLM 输出并对 LLM 自己下判断**（含幻觉维度），
并**驱动重执行**（重跑风控语义步骤），属 self-consistency / self-reflection 模式；
同时天然产出 P4 所需指标（工具纠错次数、幻觉率）。

**验证（AI 自测，非用户验收）**

| 项 | 方式 | 结果 |
|---|---|---|
| 5 条断言 | 新增 `SelfConsistencyCheckerTest` | **13/13 通过**：① 命中 + 非 APPROVE 时不命中；② 命中 + 风险等级已反映时不命中；③ 命中；④ 硬命中触发 / 中置信不触发；⑤ 幻觉命中 + 引用真实票号不触发 + 不同长度数字不触发；干净流水线全通过且报告断言条数；空步骤不误判；findings 结构化转换与矛盾提示生成 |
| 收尾 + 自纠错链路 | 新增 `AgentOrchestratorSelfCheckTest` | **5/5 通过**：自校验通过时收尾不抛异常且结果落库、AUTO_PASS 正常标记成功；命中矛盾时重置并**直接分发**（回归护栏）、不误标成功；超限转人工；`toResultMap` 含幻觉标记 |
| 全量构建 | `mvn -o clean install`（19 模块） | **BUILD SUCCESS**；agent-core **166 例**（135 + 13 + 5 + 其他） |
| 迁移 | 已在真实 `finaudit` 库执行 | 退出码 0，`correction_count`（int NOT NULL DEFAULT 0）与 `self_check_result`（json）已建出；**重复执行退出码 0（幂等）** |

**过程中单测抓到的一个误报（已修）**

断言⑤ 初版只用「数字长度与真实票号一致」做形态判断，结果把日期 `20260901`（8 位）
误判成幻觉票号 —— 真实票号 `07632553` 也是 8 位。已补日期形态排除（`yyyyMMdd`）。
设计取舍：**宁可漏判，也不要让自校验把正常结论误判成矛盾而触发无谓重跑**（重跑会多烧一次 LLM Token）。

**⚠️ R5-6（运行时验证暴露的致命缺陷）：自纠错重跑写成自调用，无限递归至 StackOverflow**

**现象**：R5 上线后提交一张单，任务 **8 步全部 SUCCESS 却永久卡在 RUNNING**，
`agent_task.result` 与 `self_check_result` 均为 NULL，且未建工单 —— 收尾整体没完成。

**排查过程**（值得记，因为我一开始猜错了方向）：
1. 先怀疑「列映射不一致」「自校验把结果判空」「事务边界」——逐项排除：列与实体一致（`self_check_result` json）、
   步骤 6/7/8 输出齐全（LLM 确实产出了结论）、`onToolResult` 非事务。
2. **关键线索**：`result` 也是 NULL。`result` 在 `finalizeSuccess` 一开始就 `put` 了，
   若收尾跑完必然有值 ⇒ **`finalizeSuccess` 内部抛了异常**，且因为 `markSuccess` 在最后，
   任务保持 RUNNING。这一条线索同时解释了全部现象。
3. 不再靠读代码猜，直接写测试复现（`AgentOrchestratorSelfCheckTest`，通过公开入口 `onToolResult` 驱动收尾），
   一次就拿到确凿异常：
   ```
   java.lang.StackOverflowError
     at SelfConsistencyChecker.check
     at AgentOrchestrator.passSelfCheckOrCorrect
     at AgentOrchestrator.finalizeSuccess
     at AgentOrchestrator.continueTask
     at AgentOrchestrator.passSelfCheckOrCorrect     ← 回到起点，无限递归
   ```

**根因（我的实现缺陷）**：命中矛盾后重置步骤再调 `continueTask(task.getId())`。
但 `continueTask` 内部会**重新 `listByTask`**，而在**同一事务**里读到的仍是**重置前的 SUCCESS 状态**
⇒ 它判定「所有步骤已完成」⇒ 再次进入 `finalizeSuccess` ⇒ 自校验再次失败 ⇒ 再次重置 ⇒ 递归至栈溢出。
**任务因此永远收不了尾** —— 这是个会让整条流水线静默停摆的致命缺陷。

**修复**：重置后**重新加载步骤并直接 `dispatch` 第一个 PENDING 步骤**，不再经过 `continueTask`：

```java
// ⚠️ 重置后【不能】调用 continueTask：它内部会重新 listByTask，而在同一事务里读到的
//    仍是重置前的 SUCCESS 状态，于是又走到 finalizeSuccess → 再次自校验失败 → 再次重置，
//    形成无限递归（实测 StackOverflowError）。
resumeFromFirstPendingStep(task);   // 直接分发第一个 PENDING 步骤
```

并补 `AgentOrchestratorSelfCheckTest`（5 例）作为回归护栏，其中
`rerunPathDispatchesPendingStepInsteadOfReenteringContinueTask` 专门断言「重置后走 dispatch 而非重回收尾」。

> **教训一**：**在同一事务里「改状态 → 再重新查询驱动流程」是危险模式**——查到的可能是未刷新的旧状态。
> 改状态后若要靠查询驱动后续，要么按刚改后的明确意图直接分发，要么确保查询走新事务。
> **教训二**：**private 的收尾路径必须通过公开入口补测试**。该缺陷单测全绿、端到端才暴露，
> 而端到端现象（任务卡 RUNNING、字段全 NULL）本身不指向根因 —— 是靠写测试复现才拿到栈的。

**⚠️ R5-7：修掉递归后现象仍在 → 改为给自校验加兜底（并留下真实栈）**

修掉递归（R5-6）并重启后，**现象完全一样**：任务 8 步全 SUCCESS、卡 RUNNING、`result` 与
`self_check_result` 全 NULL、不建工单。说明递归不是唯一原因，收尾链路上还有别的异常。

**逐个排除的四个嫌疑**（全部新增了测试，均通过）：

| 嫌疑 | 测试 | 结果 |
|---|---|---|
| 递归未除净 | `AgentOrchestratorSelfCheckTest` | 已修；断言「重置后走 dispatch 而非重回收尾」 |
| 编排器收尾逻辑本身 | `AgentOrchestratorFinalizeIsolationTest` 3/3 | 全桩成功时收尾不抛异常 → 排除编排逻辑 |
| 校验器消费真实数据 | `SelfConsistencyCheckerRobustnessTest` 4/4 | 用**从真实流水线复制的 output 形态**（含 LLM 长文本、7 条嵌套 duplicates、深层嵌套 200 项）与畸形形态驱动，均不抛 → 排除校验器 |
| 自校验结果落库 | `AgentTaskServiceSelfCheckPersistTest` 4/4 | 真实 `TableInfo` 下 wrapper 能解析 `self_check_result` 列与 JSON 类型处理器 → 排除落库 |

单测层面全部排除，但真实栈拿不到（agent-core 日志只进 IDE 控制台、不落盘）。

**处置：加兜底 + 留栈（这也是本来就该有的设计）**

自校验是**增强环节**，其自身故障**绝不允许阻断收尾**——否则单据永远审不完。
故在 `passSelfCheckOrCorrect` 外包 try/catch：任何异常都 `log.error` 打印**完整堆栈**并**放行收尾**，
由后续确定性判定（`ReviewFlowDecider`）与人工兜底继续把关。自纠错动作（重置/计数）同样加兜底。

> 这个兜底不是"掩盖问题"：它把一个**让流水线静默停摆的致命缺陷**降级为**可观测的告警**。
> 重启后日志会给出真实栈，据此可继续定位；同时即使还有未知故障，单据也不再永久卡死。

**🔶 R5-8（修复正确、但归因错误，真实根因见 R5-9）：重置步骤只改了 DB、没同步内存对象**

加上兜底后重启复验，现象收敛为：**`self_check_result` 已落库且含矛盾清单**（自校验确实执行、
断言④ 精确命中「硬命中重复 + 置信度 0.95」），但 **`correction_count` 恒为 0**、
工单原因里没有「自校验未通过」——说明自纠错分支没走通。

**定位方法（这次没再靠猜）**：写了一个**用真实 service 逻辑（spy）+ 可变步骤对象**驱动的收尾测试，
它一次就给出了确切断言：

```
AssertionFailedError: 第 7 步（风控）应被重置 ==> expected: <PENDING> but was: <SUCCESS>
```

**根因**：`resetForSelfCorrection` 只通过 wrapper 更新了**数据库**，**没有同步内存里的步骤对象**。
而 `AgentOrchestrator.finalizeSuccess` 收到的 `steps` 列表与该方法入参是**同一批对象引用**——
重置后下游再读它，看到的仍是 `SUCCESS`，于是判定「没有待重跑步骤」（`reset == 0`），
走进「无可重置步骤 → 转人工」分支；该分支内部又抛了异常被兜底放行，
最终由 `ReviewFlowDecider` 正常建单，形成"自校验判定不一致但什么都没发生"的假象。

**修复**：重置时**同步内存对象**（状态转 PENDING、清空输出/错误、重试归零），
使内存状态与 DB 一致，下游才能看到 PENDING 并真正重跑。

**回归护栏**：`AgentTaskStepResetSqlTest.resetSyncsInMemoryStepState` 直接断言
「重置后内存对象状态必须为 PENDING 且输出已清空」——这条断言若无修复必然失败。

> **教训（与 R5-6 同源）**：**「改状态」必须同时覆盖 DB 与内存两种表示**。
> R5-6 是「改完后重新查 DB 拿到旧值」，R5-8 是「只改 DB 没改内存」——
> 两个缺陷都源于同一件事：**混淆了「持久化状态」与「调用方持有的对象状态」**。
> 凡是 service 方法修改了调用方传入的实体，就应当考虑是否要同步回内存对象。

**✅ R5-9（真正的根因，已修复）：wrapper `set(jsonColumn, value)` 不带 typeHandler，写 JSON 列必失败**

R5-8 修复 + 重启后复验，现象**一模一样**：`self_check_result` 有值、`correction_count=0`。
这次不再猜，改用**在真实 MySQL 上直接复现**的方式定位：写了一个独立 Java 程序，
用与生产同一套 MyBatis-Plus Mapper/实体（仅手工装配 `SqlSessionFactory`）对真实库跑一遍
`decideBySelfCheck` 里的每一步 DB 操作，结果一次给出全部答案：

| # | 复现的操作 | 结果 |
|---|---|---|
| 1 | `resetForSelfCorrection` 原样 wrapper（含 `set(output, null)`） | ✅ OK（`set(col, null)` 无问题） |
| 3 | `updateInputParams(Map)` 原样 wrapper | ❌ `MysqlDataTruncation` |
| 5 | `applySelfCheckResult(Map)` 原样 wrapper | ❌ `MysqlDataTruncation` |
| 8 | `prepareRerun` 原样 wrapper（`set(inputParams, map)`） | ❌ `MysqlDataTruncation` |
| 6 | 修复方案：实体补丁更新写 `self_check_result` | ✅ OK |
| 7 | 修复方案：实体补丁更新写 `input_params` | ✅ OK |

异常原文：

```
org.apache.ibatis.exceptions.PersistenceException:
### Error updating database.  Cause: com.mysql.cj.jdbc.exceptions.MysqlDataTruncation:
Data truncation: Cannot create a JSON value from a string with CHARACTER SET 'binary'.
```

**根因**：`LambdaUpdateWrapper.set(列, 值)` 生成的参数**不携带 typeHandler**（`formatParam` 只把值塞进
`paramNameValuePairs`）。MyBatis 只能把 `Map` 当未知对象交给 JDBC 驱动，驱动按 **binary 字符集**发送字符串，
MySQL 5.7 的 JSON 列直接拒绝。而实体字段带 `@TableField(typeHandler = JacksonTypeHandler.class)`，
经**实体更新**时 MP 渲染 `#{et.xxx,typeHandler=JacksonTypeHandler}`，序列化为 utf8 字符串后正常入库——
这也是为什么 `result`、`review_findings` 等列一直没出问题（它们都走实体更新）。

**为什么这个缺陷极难定位**（三重伪装）：

1. 异常发生在 `applySelfCheckResult`（自校验闸口的**第一行落库**），被 R5-7 的兜底 catch 吞掉，
   于是 `decideBySelfCheck`（**真正做纠错的地方**）整段根本没被执行——重置、注入、计数、重跑全部没发生；
2. `self_check_result` **却有值**：`applySelfCheckResult` 会先 `task.setSelfCheckResult(result)` 同步内存，
   随后 `markApprovalPending(task, ...)` 用**实体**更新，把这个内存字段（带 typeHandler）顺带写进了库——
   于是「落库成功」的表象把「写入语句其实每次都抛异常」完美掩盖了；
3. `result` 里的 `selfCheck` 键也正常存在（写在抛异常之前），判据 A 全绿。

**修复**（统一原则：**JSON 列一律经实体更新，禁止出现在 wrapper 的 `set` 片段里**）：

| 位置 | 原写法 | 新写法 |
|---|---|---|
| `AgentTaskService.applySelfCheckResult` | `set(AgentTask::getSelfCheckResult, map)` | `AgentTask.selfCheckResultPatch(id, map)` 实体补丁 |
| `AgentTaskService.prepareRerun` | `set(AgentTask::getInputParams, map)` | `AgentTask.inputParamsPatch(id, map)` 实体补丁 + wrapper 负责其余列 |
| `AgentTaskStepService.updateInputParams` | `set(AgentTaskStep::getInputParams, map)` | `AgentTaskStep.inputParamsPatch(id, map)` 实体补丁 |

> 补丁实体**只带主键 + 目标 JSON 列**，绝不把整个实体写回（否则会把并发期间的其他字段覆盖成脏值）。
> 置 NULL 仍走 wrapper 的 `set(col, null)`——实测 null 值不会触发该问题（列 1/2 均 OK）。

**⚠️ 同一根因牵出的潜伏缺陷（R4 遗留，非 R5 新增）**：`prepareRerun` 用在「工单驳回 → 提交人修改后重跑」
（amend 重跑）链路上。它原样写法**必然抛异常**，即该功能在真实库上一直不可用——
R4 阶段的验证脚本没有覆盖 amend 重跑，故未被发现。本次一并修复。

**amend 链路运行时复验证据（2026-09-21，`docs/test/r5-amend-rerun-e2e.ps1`，19/19 PASS / 0 SKIP）**

完整走了一遍真实链路：财务驳回 → 提交人改金额 50107 → 7000 → 同单重跑。

| 环节 | 结果 |
|---|---|
| 财务驳回（`POST /api/v1/audit/tickets/{id}/reject`） | code=0；工单 `REJECTED`、任务 `REJECTED`、报销单 `FAILED` |
| 提交人修改重跑（`POST /api/v1/reimbursements/{id}/resubmit`） | **code=0**（修复前该调用必然失败，见上） |
| `agent_task.input_params.claimedTotal` | **7000** —— prepareRerun 写 JSON 列成功的直接证据 |
| 工单 | `AMENDED`（rerun_count 0→1→2）、`adjusted_amount=7000.00` |
| 步骤重规划 | 有效 8 条 + 已软删 16 条（`replan` 全量重建确实执行），与 `total_steps` 一致 |
| `audit_record` | `action=AMEND` 且 `before_data`/`after_data` 均非空（顺带验证两个 JSON 列） |
| 重跑结果 | 无 FAILED 步骤、任务 `APPROVAL_PENDING`、`self_check_result` 已落库（自校验在重跑链路上同样生效） |
| 收口 | 工单由 `AMENDED` 复位 `PENDING`（**不是死端**）、报销单 `total_amount=7000` |

> 脚本首版把端点写成 `/api/v1/audit-tickets/{id}/reject`（实为 `/api/v1/audit/**tickets**`），
> 得到 404 空响应，被 `ConvertFrom-Json` 解析成空对象后**静默当成「驳回失败」SKIP**，
> 于是 16/16 全绿却根本没测到驳回环节。教训已固化为两处改造：
> ① 两个脚本的 `Api()` 对「非 R 结构响应」统一包装成可诊断的 `HTTP <status> <body>`；
> ② 驳回类前置步骤失败时打印原始响应，禁止静默降级为 SKIP 而不留痕。

**回归护栏**（不连数据库，用 MyBatis 真实解析出的 SQL 断言纪律）：
`JsonColumnTypeHandlerGuardTest` 四条断言：实体补丁写 `self_check_result` / `input_params` 的参数
必须是 `JacksonTypeHandler`；**反面证据**：`wrapper.set` 的同列参数**不得**带 `JacksonTypeHandler`
（把这个「看起来完全正常」的写法钉死）；`TableInfo` 确实登记了 typeHandler。

> **教训 1**：**「SQL 看起来对」不等于「能跑」**——`wrapper.getSqlSet()` 完全正常，
> 报错在驱动层参数绑定。凡涉及非字符串列（JSON/枚举/自定义 typeHandler），必须用**真实库**
> 验证一次，mock 单测永远抓不到。本次能一次定位，靠的就是「用真实 Mapper + 真实库跑最小复现」。
> **教训 2**：**兜底 catch 必须留下可观测的痕迹**。R5-7 的兜底本身是对的（防止单据永久卡死），
> 但它把致命异常降级成了控制台一行日志（而 agent-core 日志不落盘、只输出到 IDE 控制台），
> 等于把故障藏了起来。故本节配套 R5-11（轨迹落库）。

**✅ R5-10（同一轮排查中发现并修复）：矛盾提示注入到了 TOOL 步骤，从未到达 LLM**

`injectSelfCheckHint` 原先只按 `agentRole == RISK_AUDITOR` 找注入目标，而风控角色下**既有 TOOL 步骤
（`duplicate_check`）又有 LLM 步骤（风控语义判断）**，`findFirst` 命中的是 stepNo 更小的 TOOL 步骤。
只有 `executeLlmStep` 会读 `inputParams` 组装 prompt，工具不认 `selfCheckHint` 字段——
即使重跑真的发生，LLM 拿到的上下文与首次完全一致，**只会给出同样的结论（重跑等于白跑）**。

**修复**：注入目标改为「`stepType = LLM` 且 `agentRole = RISK_AUDITOR`」的步骤；
找不到时退化为重跑范围内的**第一个 LLM 步骤**（结论汇总），并在日志中说明；同时把注入到的步骤 ID
写进自校验轨迹（R5-11），可直接看到「提示到底进了哪一步」。

**✅ R5-11（配套加固）：自校验/自纠错轨迹落库，不再依赖 IDE 控制台**

R5 前后三次误判根因，共同的障碍是：**agent-core 的日志只在开发机 IDE 控制台，既不落盘也无法被
验证方读取**。故把自校验的执行轨迹写进任务结果 `agent_task.result.selfCheckTrace`（字符串数组），
内容包括：自校验是否执行完成、`coherent`、命中矛盾数与断言名、是否进入自纠错分支、`riskStepNo`、
重置了哪些步骤、提示注入到哪一步、第几次重跑、重跑起点，以及**任何兜底 catch 捕获的异常原文**。

`docs/test/r5-self-check-e2e.ps1` 据此新增**判据 C**：`selfCheckTrace` 必须存在，
且其中**不得出现**「自校验执行失败 / 自纠错动作执行失败」——这一条正是 R5-9 这类
「异常被吞、链路静默失效」故障的直接检测器（此前判据 A 全绿而链路实际未执行）。

**R5 现状小结（诚实标注）**

- 已确认可用（单测 + 真实运行时）：5 条断言的判定逻辑、`self_check_result` 落库、安全兜底
- **已修复并在真实运行时复验通过**：R5-6 递归、R5-8 内存状态未同步、**R5-9（真正根因）JSON 列写入方式**、
  R5-10 提示注入目标、R5-11 轨迹落库 → 自纠错重跑**确实发生**
- 单测：agent-core 175 个用例全绿（含新增 `JsonColumnTypeHandlerGuardTest` 4 条护栏）
- R5-5 基线实测未做（见下）

**运行时复验证据（2026-09-21，taskId=400683，脚本 PASS=9 / FAIL=0）**

| 证据 | 值 |
|---|---|
| `agent_task.correction_count` | **1**（修复前恒为 0） |
| `tool_execution_log` 中 `duplicate_check` 执行次数 | **2 次**（23:39:45 首次 → 23:39:49 重跑）——重跑确实发生的最硬证据 |
| step 7（风控 LLM）`input_params` | 含 `selfCheckHint`（R5-10 修复生效） |
| step 6（TOOL `duplicate_check`）`input_params` | `{"reimbId":74}` 保持干净（修复前提示会错误注入到这里） |
| `result.selfCheckTrace` | `自校验执行完成：coherent=false，断言 5 条，矛盾 1 条，历史纠错 1 次 [DUPLICATE_HIGH_VS_HIGH_CONFIDENCE]` → `已达纠错上限 1 次，转人工复核` |
| 重跑后结果 | LLM 仍给 0.98 置信度（≥0.9）→ 矛盾仍在 → 按上限转人工 → 工单 `review_reasons` 末条含 `RISK_HIT:自校验未通过[DUPLICATE_HIGH_VS_HIGH_CONFIDENCE]…` |

> 注意：本次 LLM 在**拿到矛盾提示后**仍给出更高的置信度（0.93/0.95 → 0.98），
> 属模型行为而非链路缺陷——系统按设计「上限 1 次 → 转人工」收口，这正是希望的行为
> （自主纠错不追求「一定说服模型」，而是**发现不一致 → 重跑一次 → 仍不一致就交给人**）。

**待办**

- [x] 迁移已在真实库执行（自校验两列）
- [x] R5-9 复现与修复（真实库最小复现程序 + 实体补丁写法）
- [x] **重启复验通过**（taskId=400683，脚本 9/9 PASS）：`correction_count=1`、`duplicate_check` 执行 2 次、
      轨迹无异常、重跑后仍矛盾 → 工单含「自校验未通过」
- [x] **amend 重跑链路复验通过**（`r5-amend-rerun-e2e.ps1` 19/19 PASS）：驳回 → 改金额 → 同单重跑 → 工单复位 PENDING
- [ ] **遗留卡死任务**：`agent_task.id=400675` / `400676` / `400679` 等是 R5-6/R5-9 的受害者
      （8 步全 SUCCESS 但永久 RUNNING、无 result）。属测试残留，可人工作废或忽略
- [ ] **R5-5 AUTO_PASS 基线实测未做**：需构造 20~30 张小样本单据统计自动通过率与 reviewReason 分布。
      依赖**真实 LLM 调用与百度 OCR 配额**（免费版有 QPS 限制），且样本量会显著消耗配额，
      故留待有稳定配额时执行；届时据此决定是否放宽 `confidence<0.7`（决策 5）
- [x] R6-1：`GENERIC` 路径已接入自校验与结果分支（见下 R6 记录）
- [ ] R6/R7：把「JSON 列写入纪律」（禁止 wrapper `set` 写 JSON 列）补进 `AGENTS.md` §5 代码规范，
      与既有的「批量新增统一 XML」同级（单测护栏 `JsonColumnTypeHandlerGuardTest` 已生效，
      对外约定已写入 `docs/architecture/conventions.md` §2.2，`AGENTS.md` 正文待补）

---

### R6 · 结构与契约 —— ✅ 运行时复验通过（已提交 `610f634`，双仓已推送）

R6 是「结构与契约」阶段：不新增业务能力，而是把前六轮堆出来的结构问题收口，
让后续（R7 文档、R9 P4 数据源、以及未来的 Nacos 化）有稳定地基。

| 序 | 改动 | 关键点 |
|---|---|---|
| R6-1 | **GENERIC 产品化** | `finalizeSuccess` 的 GENERIC 分支改为与报销同规格：先过 `passSelfCheckOrCorrect`，再按 `ReviewFlowDecider` 分支；`NEED_REVIEW` 时同样 `enterApproval` 建工单（`trigger_type` 归 `RISK_HIT`）。差异只保留两点：不做预算占用、报销单回写 no-op |
| R6-1b | **自校验与角色解耦** | `SelfConsistencyChecker` 原先只在 `agentRole=SCHEDULER` 的步骤上取汇总结论、只扫 SCHEDULER/RISK_AUDITOR 的自由文本；GENERIC 任务的 LLM 步骤 `agentRole` 为 null → 自校验整体空转。现改为「优先 SCHEDULER，无则取 **stepNo 最大的 LLM 步骤**」，自由文本扫全部 LLM 步骤 |
| R6-2 | **工具租户基准重做** | 新增 `ToolTenantCredential`（权威租户 / 声明租户 / taskId 分离）；HTTP 链路权威租户取网关-JWT 派生上下文（`ToolController.requireAuthTenant` 缺失即拒绝），MQ 链路改用**任务归属反查**（新增 `AgentCoreServiceFeign.findTaskTenantId` → `/internal/audit/tasks/{id}/tenant`，库内事实，与消息声明相互独立） |
| R6-3 | **工具缓存租户隔离 + 降级** | key 由 `tool:exec:{code}:{hash}` 改为 `tool:exec:{tenantId}:{code}:{hash}`（原写法两个租户同入参会**命中同一条缓存**，属跨租户数据泄漏）；Redis 读/写改为 `cacheGetQuietly`/`cacheSetQuietly`（故障只告警，不阻断工具执行） |
| R6-4 | **工具出参契约** | `tool_registry` 增 `output_schema`；执行器返回后校验（无 schema 跳过）；注册口新增 Schema 合法性与强度校验（必须 `type=object`，入参还必须有非空 `properties`）；`budget_query` 入参 Schema 由「必填 deptName」改为「deptName/deptId 二选一」（与工具实现、防越权守卫对齐） |
| R6-5 | **流水线声明化** | 新增 `FlowDefinition` / `FlowStepDefinition` / `StepType`；`RuleBasedFlowEngine` 退化为「持有声明 + 物化」，8 步结构与两条排序约束（票据核验紧跟规则校验、两个 LLM 步骤在末尾）成为可读数据 |
| R6-6 | **表述澄清** | `AgentRole` 注释与 `task-orchestration.md` 新增 §0：**多 Agent = 单进程内角色化，非跨服务 A2A**（无独立进程/记忆/协商协议；跨进程的只有 TOOL 步骤经 MQ 交 tool-service） |
| R6-7 | **文档入口** | 补 `common-jwt-starter` / `common-mq-starter` README（此前 8 个 Starter 里唯独这两个缺失）；新增 `docs/architecture/conventions.md`（约定 + 每条的由来与代价）；README 与架构索引补规范入口 |

**验证证据（本机）**

- `mvn -o clean install` 19 模块 BUILD SUCCESS；agent-core **187 例**（+12）、tool-service **45 例**（+20）全绿
- 新增单测：`FlowDefinitionTest` 5、`AgentOrchestratorFinalizeIsolationTest` 3（GENERIC 三态）、
  `SelfConsistencyCheckerTest` +4（无角色结论采集）、`ToolAccessGuardTest` 21（重写，含真实可触发的两类不一致 +
  部门定位二选一）、`ToolExecutionServiceCacheTest` 4、`ToolSchemaContractTest` 8

**运行时复验证据（2026-09-22，迁移 §13~15 已执行 + 两服务重启后）**

| 验证项 | 结果 |
|---|---|
| `r6-generic-task-e2e.ps1`（autoPass 场景，taskId=400684） | **9/9 PASS**：`flowBranch=AUTO_PASS`、无工单、任务 SUCCESS、轨迹「自校验执行完成 → 自校验通过，放行收尾」 |
| `r6-generic-task-e2e.ps1`（needReview 场景，taskId=400685） | **9/9 PASS**：数据含同票号两次付款 → `flowBranch=**NEED_REVIEW**`、**工单 `RISK_HIT`/PENDING 已建**、任务 `APPROVAL_PENDING` ← R6-1 的核心新行为 |
| `r5-self-check-e2e.ps1`（taskId=400686） | **9/9 PASS**（回归）：`correction_count=1`、重跑确实发生、工单含「自校验未通过」 |
| `r5-amend-rerun-e2e.ps1`（taskId=400686） | **19/19 PASS / 0 SKIP**（回归）：驳回 → 改金额 → 同单重跑 → 工单复位 PENDING |
| R6-2 探针：直连 9202 无租户头（带 `X-User-Id` + `tool:execute`） | `400 缺少登录上下文（权威租户不可信），请通过网关携带 JWT 访问` ✅ fail-closed |
| R6-2 探针：直连 9202 身份头齐全 | `code=0` 正常执行 ✅（顺带证明 `amount_verify` 出参校验在真实路径放行合法出参） |
| R6-4 探针：经网关只传 `deptId` 调 `budget_query` | **`code=0`**，返回 `deptName=研发部`（由 deptId 反查）——三层契约（工具实现 / 入参 Schema / 防越权守卫）口径一致 |
| `budget-occupancy-concurrency.ps1` | 7/7 PASS（回归） |
| `bizno-collision-retry.ps1` | 4/4 PASS（回归） |
| `r2-audit-timestamp-check.ps1` | 2/2 PASS（回归）：`seen_count 32→33`、`updated_at 00:17:23→00:17:27` |

**联调期发现并修复的第六处契约不一致（R6-4 收尾）**：`budget_query` 的部门定位方式在三个层次里写了两套口径——
工具实现（`BudgetQueryTool` L53）与入参 Schema 都接受「deptName 或 deptId」，唯有 `ToolAccessGuard.checkDeptOwnership`
硬性要求 deptName。表现为：调用方按 Schema 只传 deptId 时，报错是 `budget_query 部门不能为空`，
**从报错里根本看不出自己违反了哪条契约**。已改为「两层都缺才拒绝」，与 Schema 必填口径一致，并补单测
`budgetQueryAcceptsDeptIdOnly` / `budgetQueryRejectsWhenNeitherDeptNameNorDeptId`。

**⚠️ 交付顺序（必须先迁移再重启）**

`ToolRegistry` 实体新增 `outputSchema` 字段后，**tool_registry 的所有查询都会带上该列**——
若 DB 里没有 `output_schema` 列，tool-service 会直接报 `Unknown column`。故执行顺序为：

1. 执行 `docs/database/migration-P3.8.sql`（§13 加列、§14 更新 `budget_query` 入参 Schema 与 `amount_verify` 出参 Schema、§15 核对）；
2. 重启 **tool-service**（9202）与 **agent-core-service**（9201）；
3. 跑 `docs/test/r6-generic-task-e2e.ps1`（两个场景：默认 autoPass / `-Scenario needReview`）；
4. 回归 `docs/test/r5-self-check-e2e.ps1`、`docs/test/r5-amend-rerun-e2e.ps1`、`r2-audit-timestamp-check.ps1`。

**待办**

- [x] 迁移 §13~15 已在真实库执行并核对（output_schema 列 / budget_query anyOf / amount_verify 出参 required）
- [x] 两服务重启后完成 R6 端到端复验 + R5/R2/R1 回归（见上表）
- [x] R6-4 收尾：`ToolAccessGuard` 部门定位改为「deptName 或 deptId 二者任一」，与工具实现和 Schema 对齐
- [ ] R6-1 前端「智能分析」页（`views/analysis/create.vue`）：按 §7 后端先行，登记在前端阶段
- [ ] 把 `ToolTenantCredential` 的租户口径补进 `docs/architecture/tenant-auth.md`（R7-9 文档收口时一并做）




