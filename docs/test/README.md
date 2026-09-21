# 测试与验收文档

> `docs/test/*.ps1` 是本项目的**端到端/专项验收脚本**（PowerShell）。
> 定位：单测覆盖不到的真实链路问题（装配层丢字段、表结构建模缺陷、驱动层行为、JSON 列写入方式）——
> 这类缺陷**单测全绿也照样存在**，只能靠端到端暴露。
> 相关：部署与前置条件见 [`docs/deploy/README.md`](../deploy/README.md)，错误语义见 [`docs/api/README.md`](../api/README.md)。

## 1. 前置条件（所有脚本通用）

| 前置 | 说明 |
|---|---|
| 后端 5 服务已启动 | `agent-gateway` 9080 / `agent-core-service` 9201 / `tool-service` 9202 / `tenant-service` 9203 / `file-service` 9205（见 deploy §5） |
| 数据库迁移已执行 | 至少执行到 `docs/database/migration-P3.8.sql`（脚本会直连 MySQL 断言库内状态） |
| `mysql` 客户端可用 | 脚本用 `mysql -N -B -e` 直连库断言；Windows 上通常不在 PATH，需用 `-MySqlExe` 指绝对路径 |
| `curl.exe` 可用 | **仅 `r1-budget-occupancy-e2e.ps1`** 需要（multipart 附件上传走 `curl`），缺失时会明确报错退出 |
| 真实 LLM 与 OCR 可用 | 需要 `FINAUDIT_MODEL_API_KEY` 与百度 OCR AK/SK（配额有限，见 §5） |
| OCR 样本图存在 | `docs/ocr-samples/local/` 下需有 `.jpg`/`.jpeg`/`.png` 样张；脚本找不到会直接 `throw "未找到 OCR 样本"`。⚠️ **`local/` 已被 `.gitignore` 排除、不入版本库**（真实发票含敏感信息）——克隆后需自行放入样张，见 [`docs/ocr-samples/README.md`](../ocr-samples/README.md) |
| 前端**不需要**启动 | 全部脚本走 HTTP + MySQL，不经浏览器 |
| PowerShell | Windows PowerShell 5.1 或 PowerShell 7 均可；**脚本文件必须是 UTF-8 with BOM**（见 §4） |

> ⚠️ 脚本会**真实写入数据**（提交报销单、上传附件、建工单、占预算），请在开发/测试库上跑。

## 2. 脚本清单（共 11 个）

| # | 脚本 | 覆盖 | 判据（关键断言） | 退出码 |
|---|---|---|---|---|
| 1 | `r1-budget-occupancy-e2e.ps1` | R1 预算真实占用 → 释放全链路 | 走网关提交报销单 → 流水线 → `budget_occupancy` 落 OCCUPIED/RELEASED → 与 `budget.used_amount` **对账**；配平口径 `Σ(amount WHERE status='OCCUPIED') == used_amount`（RELEASED 行整条跳过，不是「Σ占用−Σ释放」） | `fail>0 → 1` |
| 2 | `budget-occupancy-concurrency.ps1` | R1 并发正确性（**直连 MySQL**，不走应用） | 对 MySQL 并发执行与 `BudgetMapper.occupy` 等价的 SQL：场景1 预算 10000 / 20 并发各 600 → 成功 16、失败 4、`used_amount=9600`；**场景2 预算 10000 / 20 并发各 6000 → 成功 1、失败 19、`used_amount=6000`（核心用例）**；场景3 释放 600 → 减 600 且不为负 | `1` / `0` |
| 3 | `bizno-collision-retry.ps1` | R1-8 单号撞库换号重试 | 轮询等到整秒瞬间 → 向 `agent_task` 插入该秒全 10000 个尾号 → 立刻提交报销单，**保证撞上**；断言 ① 提交返回 `code=0`（换号重试对外无感）② 报销单号秒段 == 被占满的秒（证明确实撞过）③ 两个单号格式合法（`R`/`T` + 18 位）。**人工判据**：agent-core 控制台出现 `BizNoInserter -- 报销单号碰撞，换号重试` 与「换号成功」两行 | `fail>0 → 1` |
| 4 | `r2-invoice-record-e2e.ps1` | R2 票号入链 + 投影幂等 | ① 新写入的 `ocr_result` JSON 含 `invoiceCode`/`invoiceNum`/`ocrDate`（老代码绝不会有，字段出现即证明新代码在跑）② `invoiceNum` 非空时 `invoice_record` 有对应行；**同一张票再提交一次 → 不新增行、`seen_count` 累加** | `fail>0 → 1` |
| 5 | `r2-audit-timestamp-check.ps1` | R2-10 审计时间戳自动填充 | 对**同一张票**连续提交两次（第二次走 `updateById` 累加 `seen_count`）→ `invoice_record.updated_at` **必须晚于 `created_at`**（修复前两者恒等）。⚠️ 若两次命中的票号不同 → 报 **SKIP（前置条件不满足，非产品缺陷）并 `exit 0`** | `fail>0 → 1`；SKIP → `0` |
| 6 | `r3-invoice-dedup-e2e.ps1` | R3 按票查重 + 票据-明细交叉核验 | ① 同一张票提交两次 → `duplicate_check` 返回 `LEVEL_HIGH`（发票号硬命中）→ 进人工复核 ② 明细金额远大于票面 → `invoice_match` 报 `AMOUNT_MISMATCH` → 进人工复核（`RULE_FAIL`）③ **B-4 回归**：正常单据不因「库里存在同额历史单」被误判重复。观察方式是**直接读 `agent_task_step.output`**（工具输出落库），不依赖日志 | `fail>0 → 1` |
| 7 | `r4-review-findings-e2e.ps1` | R4a 结构化问题项（`review_findings`） | ① 构造差旅住宿超标 → `audit_ticket.review_findings` 含**明细行下标、明细名、标准值、实际值、差额、建议** ② `trigger_type` 优先级：同时命中大额限额与差旅超标时取 `OVER_LIMIT`（非 `RULE_FAIL`）③ `reasons` 与 `findings` 条数一致，`review_reasons` 兼容字段仍在 | `fail>0 → 1` |
| 8 | `r5-self-check-e2e.ps1` | R5 语义自校验落库 + 自纠错重跑 + 轨迹 | 三级判据：**A（弱，必成立）** 任务到收尾闸口时 `self_check_result` 必落库（含 `coherent`）；**B（强）** 自校验判定不一致时 `correction_count ≥ 1`；**C（强，R5-9/R5-11 护栏）** `selfCheckTrace` 必落库且**不含**「自校验执行失败 / 自纠错动作执行失败」。支持 `-TaskId` **复检既有任务**（不重新提交，不消耗 LLM/OCR 配额） | `fail>0 → 1` |
| 9 | `r5-amend-rerun-e2e.ps1` | 「工单驳回 → 提交人改明细 → 同单重跑」链路（R5-9 顺带修复的专项验收） | [1] 财务驳回（`POST /audit/tickets/{id}/reject`，需 `audit:approve`；失败仅记 SKIP）[2] `POST /reimbursements/{id}/resubmit` **必须 `code=0`** [3] 立即校验落库：`input_params` 已换新金额（证明 `prepareRerun` 写 JSON 列成功）、工单转 `AMENDED` 且 `rerun_count=1`、步骤全量重规划、`audit_record` 有 `AMEND` 留痕 [4] 等重跑跑完：无 FAILED 步骤、自校验仍落库、报销单金额为新值、工单复位 `PENDING`/`APPROVED` | 汇总 SKIP 或 `fail>0 → 1` |
| 10 | `r6-generic-task-e2e.ps1` | R6-1 `GENERIC` 通用分析走同一套收尾闸口 | A 任务到终态（`SUCCESS` 或 `APPROVAL_PENDING`）且无 FAILED 步骤；B `self_check_result` 非空；C 轨迹不含「自校验执行失败/自纠错动作执行失败」；D **结果分支与工单一致**：`NEED_REVIEW ⇒ 有工单`、`AUTO_PASS ⇒ 无工单`。两个场景 `-Scenario autoPass` / `needReview` | `fail>0 → 1` |
| 11 | `r9-metrics-datasource-check.ps1` | R9 指标数据源真的在落数（R9-1 台账 / R9-2 耗时 / R9-3 SQL 口径） | A 结构就绪（`model_call_log` 表 + 两个 `duration_ms` 列）；B 台账有数（该任务 ≥1 行、`scene=llm_step` ≥1 行、累计 token > 0、带 `step_id`）；C 耗时落库（`agent_task.duration_ms > 0`、LLM 步骤 `duration_ms > 0`）；D `metrics.md` 的 6 条指标 SQL 均可执行。支持 `-TaskId` 复检、`-SkipSubmit` 只做结构+SQL（零配额消耗） | `fail>0 → 1` |

> 最近一次结果基线（见 [`docs/planning/refactor-agent-autonomy.md`](../planning/refactor-agent-autonomy.md) §11）：
> R1 19/19、并发 7/7、撞号 4/4、R2 6/6、时间戳 2/2、R3 5/5、R4 19/19、R5 9/9、amend 19/19、R6 9/9 ×2、R9 15/15（含台账/耗时/接出口，2026-09-22 复验）。

## 3. 参数与用法

所有脚本都以 `param(...)` 声明参数，**默认值面向本机开发环境**（网关 `http://localhost:9080`、DB `127.0.0.1:3306`/`root/root`/`finaudit`）。
只需覆盖你不一致的那几项。

### 3.1 通用参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `-Gateway` | `http://localhost:9080` | 网关地址（**必须经网关**，脚本不做直连服务） |
| `-MySqlExe` | `mysql` | **Windows 下最常需要覆盖**：mysql.exe 绝对路径 |
| `-DbHost` / `-Port` | `127.0.0.1` / `3306` | 数据库地址与端口 |
| `-User` / `-Password` | `root` / `root` | 数据库账号 |
| `-Database` | `finaudit` | 库名 |
| `-PollSeconds` | 60~180（各脚本不同） | 等待流水线跑完的最长轮询秒数；**LLM/OCR 慢时调大** |

### 3.2 示例（推荐形式）

```powershell
# R3 按票查重 + 票据核验
powershell -ExecutionPolicy Bypass -File docs\test\r3-invoice-dedup-e2e.ps1 `
    -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root

# R5 复检既有任务（不重新提交、不消耗 LLM/OCR 配额）
powershell -ExecutionPolicy Bypass -File docs\test\r5-self-check-e2e.ps1 -TaskId 400683 `
    -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root

# 预算占用并发正确性（纯 DB，不需要后端服务在跑）
pwsh -File docs/test/budget-occupancy-concurrency.ps1 -User root -Password root
```

### 3.3 脚本专有参数

| 脚本 | 参数 | 默认 | 说明 |
|---|---|---|---|
| `r1-budget-occupancy-e2e` / `budget-occupancy-concurrency` / `bizno-collision-retry` / `r2-*` / `r3-*` / `r4-*` / `r5-self-check` | `-DeptId` / `-DeptName` | `3` / `研发部` | 提交报销单用的部门 |
| 同上 | `-Period` | `2026-09` | 预算周期（`yyyy-MM`） |
| `budget-occupancy-concurrency` | `-Concurrency` | `20` | 并发线程数 |
| `bizno-collision-retry` | `-Stamp` | `COLLISIONPROBE` | 标题标记，便于检索本次造的数据 |
| `r2-invoice-record-e2e` / `r2-audit-timestamp-check` / `r3-invoice-dedup-e2e` / `r5-self-check-e2e` | `-SampleFile` | `""` | 指定样张图片路径；**留空则从 `docs/ocr-samples/local/` 随机取一张**（`r3` 会优先尝试该目录下的 `增值税发票.jpg`） |
| `r3-invoice-dedup-e2e` | `-PollSeconds` | `120` | 需覆盖 OCR + LLM，通常比 R1/R2 更长 |
| `r5-self-check-e2e` | `-TaskId` | `0` | **非 0 则复检该任务**，不重新提交（省配额） |
| `r5-amend-rerun-e2e` | `-TaskId` | `400683` | 目标任务（默认复用 R5 产生的单据） |
| `r5-amend-rerun-e2e` | `-NewAmount` / `-ClaimDate` / `-ExpenseType` | `7000` / `2026-09-01` / `OFFICE` | 重跑时写回的新明细 |
| `r5-amend-rerun-e2e` | `-SkipReject`（switch） | 关 | 跳过驳回步骤，直接从 `PENDING` 修改重跑（同样覆盖 `prepareRerun`） |
| `r6-generic-task-e2e` | `-Scenario` | `autoPass` | `autoPass` \| `needReview` |
| `r6-generic-task-e2e` | `-TaskId` | `0` | 非 0 则复检既有任务 |

### 3.4 退出码与输出约定

- **退出码即结论**：`fail > 0 → exit 1`，否则 `exit 0`。可直接用于 CI；
- 每行断言打印 `[PASS] label = 实际值` / `[FAIL] label: 实际 X，期望 Y`，末尾打印
  `=== 汇总: PASS=n FAIL=n [SKIP=n] ===`；
- **`SKIP` 是「前置条件不满足，非产品缺陷」**，脚本会打印原因与「如何正确重跑」，并以 `exit 0` 结束。
  例：`r2-audit-timestamp-check` 要求两次提交命中**同一张票**，否则累加路径压根没走到。
  设计原则见 [`conventions.md`](../architecture/conventions.md) §5.3：**禁止把「没测到」静默降级成「通过」**。

## 4. ⚠️ `docs/test/*.ps1` 必须保持 UTF-8 with BOM

**Windows PowerShell 5.1 对没有 BOM 的 UTF-8 文件按 ANSI 解析**，
脚本里的中文字符串会直接导致**语法崩掉**（不是乱码，是解析失败）。

复核方法：

```powershell
$p = "docs\test\r3-invoice-dedup-e2e.ps1"
$b = [IO.File]::ReadAllBytes($p); '{0:X2} {1:X2} {2:X2}' -f $b[0], $b[1], $b[2]   # 应为 EF BB BF
```

语法自检（改完脚本先跑一次，能提前拦住解析错误）：

```powershell
[System.Management.Automation.Language.Parser]::ParseFile($p, [ref]$null, [ref]$null)
```

> ⚠️ **绝大多数编辑器/AI 工具写回文件时会吃掉 BOM**。改完脚本**务必**按上面的方法复核三个字节。
> 这是本项目反复踩到的坑，已写进 [`conventions.md`](../architecture/conventions.md) §5.4。

## 5. 配额与成本注意事项

- 脚本 1 / 4 / 5 / 6 / 7 / 8 / 9 / 10 会**真实调用 LLM 与 OCR**，受配额限制；
- **复检优先**：`r5-self-check-e2e.ps1 -TaskId <id>`、`r6-generic-task-e2e.ps1 -TaskId <id>`
  可对既有任务重跑断言，**不消耗新配额**；
- `r5-amend-rerun-e2e.ps1` 默认复用 R5 产出的单据，避免重复上传/识别；
- 脚本会自动**避开库中已有金额**（`duplicate_check` 判据含「金额完全相等」，撞车会误判疑似重复导致断言不稳）；
- `r1-budget-occupancy-e2e.ps1` 每次提交都上传**新附件**——同一 `file_record` 只能绑定一张报销单，复用会被 file-service 拒绝。

## 6. 评估用例模板（P4 量化评估用）

> **用途**：P4「量化评估」需要可复现、可对照、可累计的用例记录。
> 目标指标（见 `docs/ProjectRequirements.md` §七）：票据识别准确率、重复报销拦截率、预算超支预警次数、**自动通过率**，
> 以及 Agent 侧的工具纠错次数、幻觉率、任务完成率。
> ⚠️ 现状：**P4 大盘尚未建设**，R5-5 的 AUTO_PASS 基线实测也**未执行**（依赖真实 LLM / OCR 配额）。
> 本模板先统一记录口径，待基线开跑时逐条填充。

### 6.1 用例记录表（一次评估 = 一张表）

| 字段 | 填写要求 |
|---|---|
| 用例编号 | `EVAL-<阶段>-<序号>`，如 `EVAL-P4-001` |
| 归属目标 | 六项目标之一（自主处理 / 报销单核验 / 发票真伪查重 / 预算占用校验 / 报销进度查询 / 驳回重提引导） |
| 场景 | 一句话描述业务情境（如「同一张增值税发票被两张不同金额单据报销」） |
| 输入 | 可复现的输入：单据 JSON / 样张文件名 / 部门 + 期间 + 金额 / `taskId` |
| 期望判据 | **可查询的事实**，不是日志文本。必须写清「查哪张表哪个字段、等于什么」（如 `agent_task_step.output->>'$.dupLevel' == 'LEVEL_HIGH'`） |
| 实际结果 | 实测值（原样粘贴，不要只写「通过」） |
| 结论 | `通过` / `不通过` / `阻塞（说明缺什么）` |
| 证据 | 脚本名 + 退出码 + 关键输出行；或 SQL 查询与结果 |
| 复跑命令 | 完整命令行（含 `-TaskId` 等参数），保证他人可复现 |
| 备注 | 是否消耗 LLM/OCR 配额、是否受随机性影响、相关缺陷编号（如 `R5-9`） |

### 6.2 模板（可直接复制）

```markdown
### EVAL-P4-001 · 同一发票重复报销必须拦截

- **归属目标**：发票真伪查重
- **场景**：同一张增值税发票（invoiceNum=07632553）先后提交两张报销单，第二张金额不同
- **输入**：样张 `samples/vat-07632553.jpg`；部门 研发部(3)；期间 2026-09；第一张 1000.00，第二张 800.00
- **期望判据**：
  1. 第二张的 `agent_task_step.output->>'$.dupLevel'` == `LEVEL_HIGH`
  2. 第二张的 `agent_task_step.output->>'$.suspectedHigh'` == `true`
  3. 第二张 `audit_ticket` 存在，且 `trigger_type` 含重复相关编码
- **实际结果**：（粘贴实测值）
- **结论**：通过 / 不通过 / 阻塞
- **证据**：`docs/test/r3-invoice-dedup-e2e.ps1` 退出码 0；`=== 汇总: PASS=5 FAIL=0 ===`；
  另附 `SELECT ... FROM agent_task_step WHERE ...` 输出
- **复跑命令**：
  `powershell -ExecutionPolicy Bypass -File docs\test\r3-invoice-dedup-e2e.ps1 -MySqlExe "<mysql.exe>" -User root -Password root`
- **备注**：消耗 1 次 OCR + 2 次 LLM 调用；不依赖随机性（样张固定）
```

### 6.3 汇总表（每轮评估一份）

| 指标 | 口径（查什么） | 本轮 | 上轮 | 变化 |
|---|---|---|---|---|
| 自动通过率 | `AUTO_PASS` 任务数 ÷ 总任务数（`agent_task.status` + 是否有工单） | | | |
| 重复报销拦截率 | `dupLevel=LEVEL_HIGH` 的命中数 ÷ 真实重复样本数 | | | |
| 票据识别准确率 | `ocr_result` 关键字段（金额/日期/票号）与人工标注一致的单据占比 | | | |
| 预算超支预警次数 | `review_findings` 含预算项的工单数 | | | |
| 工具纠错次数 | `SUM(agent_task.correction_count)` | | | |
| 幻觉率 | 自校验断言⑤（LLM 引用不存在字段/单据号/发票号）命中数 ÷ 自校验执行次数（`self_check_result`） | | | |
| 任务完成率 | `SUCCESS` 任务数 ÷ 总任务数 | | | |

> ⚠️ **口径必须先定、后跑**：同一指标在两轮之间改了口径，变化值就失去意义。
> 建议每轮评估把上表与 §6.1 的用例表一并存档（可放在 `docs/test/eval/` 下，按 `yyyyMM` 分目录）。

## 7. 待补充

- 单测覆盖范围汇总（见 `refactor-agent-autonomy.md` §11「当前验证基线」与 §5 R7 的「本机验证」，两处合计基线：agent-core > 187 例 / tool-service 45 例 / common-mybatisplus-starter 4 例 / file-service 3 例；R7 新增 `AgentTaskVisibilityTest`、`SysUserRoleServiceTest` 后计数续增）
- 合成评测数据集（P4）与其标注规范
- 评估结果归档目录 `docs/test/eval/` 与自动汇总脚本
- CI 接入（当前全部为人工在本机执行；`exit 1` 已可直接作为 CI 判据）
