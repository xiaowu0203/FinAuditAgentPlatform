# 工程约定（conventions）

> 本文是**对外可公开的约定入口**：说明本仓「怎么写代码、怎么改数据、怎么验收」的硬性规则与它们的由来。
> 强制规范正文在仓库根 `AGENTS.md`（开发规范）；本文聚焦**约定本身与其代价**，供贡献者快速对齐。
> P3.8 R6-7 落地。

## 1. 分层与依赖

| 约定 | 原因 |
|---|---|
| 包结构固定 `controller / service / mapper / pojo.entity / pojo.dto / pojo.vo / enums` | 请求体进 dto、响应体进 vo，禁止混包 |
| **实体数据访问收敛到实体自己的 Service**：每个实体的 Mapper 只被其专属 Service 持有 | 否则同一实体的 SQL 会散落多处，改表结构时无法穷尽影响面 |
| Controller 只做参数装配 + **单次** Service 委托；跨实体编排只允许出现在 `AgentOrchestrator` 或独立门面 | 端点里串联多个 Service 会让事务边界与权限校验点失控 |
| 领域 Service 之间**禁止反向依赖成环** | 环会让「谁负责状态迁移」变得不可判定 |
| 转换封装在实体类：新增 `from(...)`、更新 `apply(...)` | 业务层手写 `setXxx` 组装实体会漏字段且无法集中校验 |

## 2. 数据访问：三条必须记住的坑

### 2.1 批量新增与自定义 SQL 一律写 XML
Mapper 接口只声明签名，SQL 放 `resources/mapper/<XxxMapper>.xml`；批量新增用单条多行 `INSERT`（`<foreach>` 拼 VALUES），禁止 for 循环逐行插入。

### 2.2 **JSON 列禁止用 `wrapper.set(列, 非null值)` 写入**
`LambdaUpdateWrapper.set` 生成的参数**不携带 typeHandler**，MyBatis 只能把 `Map` 当未知对象交给 JDBC 驱动，
驱动按 binary 字符集发送字符串，MySQL 5.7 直接拒绝：

```
Data truncation: Cannot create a JSON value from a string with CHARACTER SET 'binary'
```

而 `wrapper.getSqlSet()` 完全正常——报错只在**驱动绑定参数**时发生，**mock 单测永远抓不到**。
修法是走**实体补丁更新**（字段带 `JacksonTypeHandler`）：只带主键 + 目标 JSON 列，避免整实体写回造成脏写。
仓库内有护栏 `JsonColumnTypeHandlerGuardTest`（用 MyBatis 真实解析出的 SQL 断言参数 typeHandler）
与最小复现程序 `docs/test/repro/JsonColumnWriteRepro.java`。

> 置 NULL 是例外：`set(col, null)` 实测无问题（清空 `output`/`result` 走它）。

### 2.3 关联表先问「一对一还是一对多」
唯一索引决定「一行」，与「需要记录多对多」是同一张表上的冲突。
设计投影/快照表时先明确这张表要回答的关系（R3-7 就是在这里栽过：一张票被多张单据报销，单行模型存不下）。

## 3. 多租户与权限

- **租户唯一来源是网关从 JWT 派生的上下文**；服务端不得用 `defaultValue="1"` 兜底，缺失一律 fail-closed。
- **不要在业务代码里拿请求头当权威租户**：`X-Tenant-Id` 可伪造。
  需要权威租户时，要么信任网关注入的 `UserContextHolder`，要么改用**独立事实来源**
  （如 `ToolAccessGuard` 按 `taskId`/`reimbId` 反查库内归属，P3.8 R6-2）。
- 权限用 `@RequirePerm` + 操作级权限码；内部链路收敛到 `/internal/**` 且**不经网关暴露**。
- SQL 层租户隔离由 `TenantLineInnerInterceptor` 自动注入 `tenant_id`；
  自定义 XML SQL 必须显式 `@InterceptorIgnore(tenantLine="true")` 并自行带上租户条件。

## 4. 错误处理与可观测性

- **兜底可以吞异常，但不能静默**：吞掉一个致命异常后，必须把异常原文写进**可查询的持久化字段**
  （如 `agent_task.result.selfCheckTrace`）。本仓曾因为「兜底 + 日志只在 IDE 控制台」连续三轮误判根因。
- **旁路组件故障不得拖死主链路**：审计日志、结果缓存（Redis）这类旁路，失败只告警并降级
  （`saveLogQuietly` / `cacheGetQuietly`）。
- **失败路径必须回吐结果**：工具执行无论成败都要发 `tool.result(success=false)`，
  否则 agent-core 只能等任务级超时（R0-2）。
- 金额一律 `BigDecimal`，严禁 float/double。

## 5. 验收纪律（本项目最容易翻车的地方）

1. **「跑通」必须确认真实链路，而不是「接口返回 200」**：
   验收断言要落到**可查询的事实**（库内列、工具执行次数、工单状态），而不是日志文本。
2. **拒绝与 0 或某个绝对值比较**：断言「某值被更新」必须对比前后跳变，否则历史偏差会让无效修复也报 PASS。
3. **脚本里的失败要区分「业务失败」与「请求根本没到达」**：
   `Invoke-WebRequest` 异常体直接 `ConvertFrom-Json` 时，404/403 空体会被解析成空对象，
   表现为 `code=`/`msg=` 全空的哑失败，甚至被降级成 SKIP——脚本全绿而那段压根没测到。
   两个验收脚本已统一包装为 `HTTP <status> <body>`。
4. **端到端脚本必须带 BOM**（`docs/test/*.ps1`，PowerShell 5.1 对无 BOM 的 UTF-8 按 ANSI 解析）。
5. **能编译 + 单测通过**是提交前提；涉及驱动层/DB 行为的改动必须补一次**真实库最小复现**。

## 6. 提交与分支

- 提交信息：`feat: / fix: / refactor: / docs: / test: / build:` + 中文说明。
- **一个阶段一个提交**：阶段内的代码、缺陷修复、文档、验证脚本合并为单个 commit，
  提交信息用「标题 + 分节正文」承载阶段全貌（阶段是验收与回滚的最小单元）。
- 双远程（gitee + github）内容与历史保持一致。
- 密钥 / 数据库密码 / token 只走环境变量 + `.env.example`，禁止入库入仓。

## 7. 「多 Agent」的表述口径

本项目是**单进程内的角色化**（`AgentRole` + `FlowDefinition` 声明式流水线），**不是跨服务 A2A 多智能体**：
角色之间共享同一任务上下文，没有独立进程、独立记忆或协商协议；跨进程的只有 TOOL 步骤经 MQ 交给 tool-service。
对外表述按此口径，详见 [`task-orchestration.md`](./task-orchestration.md) §0。
