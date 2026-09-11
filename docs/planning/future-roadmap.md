# Future Roadmap · 全项目 TODO 归集

> 归集散落在各计划/需求文档与代码注释中的 **TODO / 后置 / 预留 / 待定** 事项，作为跨阶段优化清单的**单一真相**。
> 约定：P2/P3 代码落 TODO 注释时，同步在本表登记/更新；来源列指向原文档位置。
> 目标阶段对应分期：P2 单据闭环与审核工具 → P3 多 Agent 与审批工单 → **P3.8 业务闭环 + Agent 能力补强** → P4 可观测评估 → P5 开源打磨。

## 1. 已决策归档（P2 决策，已定结论）

| 待办 | 说明 | 来源 | 状态 |
|---|---|---|---|
| D4 文件能力载体 | **已定：新增 `file-service`（9205）承载纯文件资源**（唯一持有 `common-oss-starter`；报销域迁 agent-core 服务内直调建任务，读附件走 `FileServiceFeign`；rag-service 回归 RAG 专用空骨架，P4 填 Milvus） | P2-execution-plan §3 D4 | 已定（P2a 落地） |
| D5 OCR 厂商选型 | **已定：百度 + `common-ocr-starter`**；AK/SK 走 `FINAUDIT_OCR_BAIDU_API_KEY` / `FINAUDIT_OCR_BAIDU_SECRET_KEY` | P2-execution-plan §3 D6 | 已定（P2b） |
| D7 部门实体表 | 先 `dept_name` 字符串，独立部门表**已前置到 P3.5 R2 落地**（`sys_dept` 树形 + user/reimb/budget 挂 `dept_id` + `budget_query` 收紧） | P2-execution-plan §3 D7 / P3 §8 | ✅ P3.5 R2（已实现） |

## 2. 开发 TODO（P3 代码必落注释）

| 待办 | 落点 | 目标阶段 | 来源 |
|---|---|---|---|
| 运行中实时打断 / 修改中间结果 / 补充指令 / 终止 | `RuleBasedFlowEngine` + 工单模块 | P5+ | P3 §8 / ProjectBusiness 人机双向交互 |
| 多级审批链（资金类一级→二级） | `audit_ticket.audit_level` + 审批流 | P5+ | P3 §8 / ProjectBusiness 微调建议4 |
| 触发条件扩展（发票存疑 / 对公支付 / 跨部门分摊） | 触发规则配置化（P2 `finance_rule` 体系） | P4/P5 | P3 §8 |
| 幻觉拦截 → 正式评估体系 | 存疑标记 → P4 幻觉检测规则 + 监控大盘 | P4 | P3 §8 / 需求标准⑤③ |
| **工具幻觉：工具目录为空时 LLM 虚构工具编码** | `TaskPlanner`（规划层校验步骤工具编码）+ P3 `RuleBasedFlowEngine`（按业务绑定工具，不依赖 LLM 自由选） | **已完成（P3a）** | P2a 实测：GENERIC 任务财务工具被 `filterTools` 过滤后目录为空，LLM 虚构 `expense_checker` 致任务 FAILED（T202608152311580979）；现已采用规划层剔除 + 报销固定流水线双保险 |
| 废单查看当时票据 | `ReimbursementService.markCancelledByTaskId` / `toDetail`（报销单新增 `attachments_snapshot` JSON 列后置） | P4/P5 | 评审 TODO：作废解绑后废单详情附件区为空，历史票据引用仅存 audit_record 快照；候选「作废时持久附件引用快照 → 废单详情按快照重建 VO」，勿回改解绑语义（锁死 file_record 复用），详见代码注释 |

## 3. 代码遗留 TODO（P1 残留，跨阶段）

| 待办 | 落点 | 说明 | 目标阶段 |
|---|---|---|---|
| 分布式锁 | `common-redis-starter` `DistributedLockTemplate`（Redisson 3.47.0） | **已完成（P3b）**：审批工单动作并发控制 `audit:ticket:{id}`，锁在事务提交后释放（`executeInTx`）；锁 key 前缀 `finaudit:lock:` | P3b（已完成）/ P5 拓展其他场景 |
| 多模型 + Token 统计 + 故障切换 | `common-model-starter` `ChatClientFactory` | DeepSeek 已实现（P1.2），Qwen/Claude 对接、token 统计、备用模型切换缺（`usageSnapshot()` 生产零调用，成本不可观测） | **P3.8 铺数据源（Token 落库）** / P4 大盘消费 |

## 3.5 P3.8 阶段承接（业务闭环 + Agent 能力补强）

> 详见 [refactor-agent-autonomy.md](./refactor-agent-autonomy.md)。本阶段插在 P4 之前——它产出 P4 指标的**数据源**，且修复的都是主链路正确性问题。
> **排期原则：业务闭环（R1~R4）优先于 Agent 能力（R5~R6）**——预算是地基，且自校验断言需要业务核验产出的真实数据才有意义。

### 业务闭环（优先级最高）

| 待办 | 说明 | 来源 |
|---|---|---|
| **预算真实占用与释放** | `budget.used_amount` **全仓无写入点**（注释标"审核通过后累加"未实现）→ 只读预检；审批通过时按**原子 SQL + 超支条件**占用，全终态释放，`budget_occupancy` 记账保幂等 | 业务走查 B-1 / B-2（**业务闭环最大缺口**） |
| **发票标识符入链** | `VatInvoiceOcr` 已抽 `invoiceCode`/`invoiceNum` 但 `OcrExtractTool.normalize` 丢弃 → 补回 + `invoice_record` 投影表（绕开 MySQL 5.7 JSON 检索限制） | 业务走查 B-3 |
| **按票精准查重** | 现算法为「同申请人+整单金额相等+±30天+**商户名**比对」→ 改为一级按 `(invoice_code, invoice_num)` 硬命中 HIGH、二级金额/日期/商户 MEDIUM（仅展示不拦截） | 业务走查 B-4（判据与粒度双错） |
| **票据-明细交叉核验** | `amount_verify` 只比"明细 vs 申报总额"，OCR 金额**全程不参与校验** → 新增 `invoice_match` 工具（金额/日期/商户 vs 明细行） | 业务走查 B-5 |
| 离线发票规则验真 | 发票代码位数/校验位、税号格式、开票日期合理性（免费方案）；外部查验服务商**待商务决策**后置 | 业务走查 B-6 + 本轮决策 2 |
| **驳回重提引导** | `review_reasons` 是原始字符串 → 结构化 `ReviewFinding`（定位明细行 + 期望值/实际值/差额/建议）；前端"待修正项清单" + 修改页标红预填 | 业务走查 B-7 |
| 提交幂等 | `submit` 无幂等键 → `idem_key` + `uk(tenant_id, idem_key)` | 业务走查 B-9 |

### Agent 能力（放大器，依赖上表数据）

| 待办 | 说明 | 来源 |
|---|---|---|
| **语义自校验与自主纠错** | `SelfConsistencyChecker` 五条一致性断言（含幻觉检测）+ 矛盾命中重跑风控步骤（上限 1 次）+ `correction_count`/`self_check_result` 落库 | 需求标准③3（**当前实质未实现**） |
| **AUTO_PASS 基线实测** | 现自动通过 = 硬条件零命中 **且** 最后一步 LLM `decision==APPROVE`（两条并存入人工路径）；小样本跑出基线后再定 `confidence<0.7` 阈值 | 业务走查 B-10 |
| `GENERIC` 路径产品化 | LLM 自主规划目前仅有工作台 JSON 入口、无产品形态；升级为前端"智能分析"入口 | 需求标准③1 / 走查 A-2 |
| 工具契约对称 | `tool_registry` 增 `output_schema`（入参有 Schema、出参没有）；修正 `budget_query` 与 deptId 权威键冲突 | 走查 A-5 / P1-6 |
| 多 Agent 表述澄清 | 明确"进程内角色化，非跨服务 A2A"（诚实表述优先于夸大） | 走查 A-3 |

### 主链路止血与一致性收口

| 待办 | 说明 | 来源 |
|---|---|---|
| 主链路止血 | OCR 超时死配置、`tool_execution_log` 非空冲突、file-service 内外链路冲突、前端角色清空与 403 权限收敛死代码 | 两轮走查 P0-1 ~ P0-9 |
| 租户与权限基准修正 | `ToolAccessGuard` 租户校验恒等通过（基准改 JWT 派生）；`/audit/**` 与 `/tools` 权限收口 | 走查 P1-1 / P0-6 / P0-7 |
| 文档一致性收口 | `tool-service.md` 形状级错误、`tenant-service.md` 漏 3 端点、`file-service.md` 漏归属规则等 16 项 | 两轮走查 D-1 ~ D-16 |
| 加固清单化 | 新增 `docs/architecture/hardening-checklist.md`：超时/权限/租户/非空/契约五类逐项核对 | 本阶段方法论产出 |

### 可选增强（R8）

| 待办 | 说明 |
|---|---|
| 外部发票查验对接 | 官方/商业查验 API（按次付费，待商务决策） |
| 主动通知 | 站内信 / Webhook（现无任何推送，进度靠轮询） |
| 进度百分比与预计等待 | 现为状态点展示，无"3/7 步"进度感 |
| 报销单列表轮询 | `reimbursement/list.vue` 无轮询，RUNNING 需手刷 |

## 4. P4 阶段承接（可观测与评估）

| 待办 | 说明 | 来源 |
|---|---|---|
| 监控大盘 + ECharts 前端页 | 性能/QPS/耗时、成本/Token、成功率/任务完成率 可视化 | 需求标准⑤ + P1.5「本期不含」 |
| 量化评估报表 | 效率 / 风控 / 成本三类指标（审核准确率、拦截率、人工复核占比、Token 费用） | ProjectRequirements §七 / 需求标准⑤ |
| 定时任务（`task-job-service` / XXL-Job） | 预算刷新、过期上下文清理、评估报表生成 | 需求技术栈 / P0 计划 |
| 上下文分层治理 | Redis 临时会话、用户长期档案、Milvus 知识库 RAG、长会话摘要压缩、手动清空/重置 | 需求标准④ |
| 工具调用容错增强 | 超时、参数自动修正、熔断兜底（**P3.8 已补：OCR 超时接线、Feign 全局超时、失败路径必回 `tool.result`、自校验纠错**；熔断兜底与参数自动修正仍留本阶段） | 需求标准③2 |
| OCR 多厂商兜底 / 熔断降级 | `common-ocr-starter` `OcrService` 多实现 + 按配置顺序 failover + 连续失败熔断降级人工录入（现仅百度单厂商，D6 扩展位已留） | P2-execution-plan §3 D6 / §4 P2b |

## 5. P5 及长期（工程打磨）

| 待办 | 说明 | 来源 |
|---|---|---|
| 限流 / 熔断降级 | 网关 + 接口层，分布式限流 | 需求标准② |
| 操作日志分层 + 检索溯源 | 操作/业务/错误日志分层，按用户/租户/任务检索 | 需求标准②3 |
| FAILED 重跑 | 续跑当前仅 PENDING/RUNNING，FAILED 任务重跑留待 | P1.5-frontend-plan §6 |
| 工具治理增强 | 动态上下线、入参 Schema 强校验、高频工具结果缓存 | 需求标准③6 |
| 前端工程优化 | ~~Element Plus 按需引入~~ ✅ UI 重构分支（主 chunk 1.2MB→190KB）、~~租户/RBAC 管理页~~ ✅ P3.5；知识库管理页随 P4 | P1.5-frontend-plan 决策表 |
| 审批规则可视化配置高级版 | 审批触发规则 / 多级审批可视化 | 需求标准⑥1 |
| 安全加固深化 | 输出脱敏字段扩展、工具入参防注入深化、Prompt 注入规则库扩充（**P3.8 已收口：`/audit/**` 与 `/tools` 权限码、租户基准去 `defaultValue=1`、file-service 内外链路区分**） | 需求标准⑦ |
| 运维配套 | 数据库定时备份脚本、中间件启停脚本、README.en 补全 | 需求标准五/3 + P0 |

## 6. 已承接（无需动作）

| 项 | 说明 |
|---|---|
| 状态机 `MANUAL_REVIEW` 预留 | `finaudit-schema.sql` / `tables.md` 预留，P3 已扩展为 `APPROVAL_PENDING` / `REJECTED` |
| MinIO 本机启动 | P0 遗留，P2a 开工前启动即可（环境事项，非功能 TODO） |
