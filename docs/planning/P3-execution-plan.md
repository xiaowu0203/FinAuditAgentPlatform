# P3 多 Agent 协同与审批工单 · 执行点文档

> 版本: v0.3 ｜ 状态: **P3a/P3b（含 P3b 工作流重设计）已完成，P3c 待实施** ｜ 前置依赖: P2 完成
> 目标: 三块并进——① 5 类财务 Agent 角色化 + 规则引擎流水线；② 人机协同审批工单闭环（含审计留痕）；③ 安全风控（注入/脱敏/越权/幻觉占位）。

---

## 1. 范围

| 块 | 对应标准 | 内容 |
|---|---|---|
| **P3a 多 Agent 编排** | ③ | Agent 角色化 + `RuleBasedFlowEngine` 规则驱动流水线 |
| **P3b 审批工单** | ⑥ | 结果分支 → 工单 → 财务审批 → 留痕归档 → 回退重跑 |
| **P3c 安全风控** | ⑦ | 注入拦截 / 输出脱敏 / 工具防越权 / 幻觉拦截占位 |

## 2. 已确认决策（2026-08-15）

**决策1 多 Agent 组织形式：方案 A**
- 5 类财务 Agent（文档解析 / 预算核算 / 规则校验 / 风控审计 / 审批调度）全部在 **agent-core 内角色化**：`AgentRole` 枚举 + 角色定义（system prompt + 专属工具集 + 职责）
- 复用 P1 状态机 / 步骤 / MQ 拓扑；简单任务仍单 Agent，不强制拆分（需求标准③约束）
- **P3 不引 A2A**；A2A 延后到 P4 真正跨服务（rag 检索 Agent）时再上 spring-ai-alibaba

**决策1b 编排取向：规则引擎驱动固定流水线**
- 业务流程固定：`解析 → 预算 → 规则校验 → 风控`，**调度 Agent = 规则引擎统筹，不靠 LLM 拆解**
- LLM 仅在语义判断节点介入：OCR 结果解读 / 规则匹配解释 / 风控语义判断
- 避免 P1 踩过的「LLM 拆解导致步骤乱/顺序漂移」

**决策2 审批工单**
- 触发条件第一版三类：大额阈值 / 规则校验不通过（超标）/ 风控命中；其余（发票存疑/对公/跨部门分摊）靠 P2 规则配置化扩展
- 财务「改金额」→ **回退流水线重跑**（自主纠错闭环）
- P3 只做工单审批（通过/驳回/改金额/补备注/终止）；**运行中实时打断后置**（TODO）
- 多级审批：P3 单级 + 表预留 `audit_level` 字段（TODO）
- **（2026-08-19 P3b 工作流重设计修正）**：重跑改由**提交人**发起（resubmit，财务仅 approve/reject/terminate，amend 移除）；新增撤回/撤销链路；每次动作留痕带前后数据快照；可见性统一（finance 看全量、普通用户看自己）。详见 §4 P3b 条目。

**决策3 安全风控**
- 注入拦截：落 **agent-core**（prompt 拼接前 `PromptInjectionGuard`），网关不拦
- 输出脱敏：后端统一 `@Mask` 注解 + 序列化切面（对外 VO 层），脱敏身份证/银行卡/税号/手机号，**金额不脱敏**；内部 Feign/MQ 保持明文
- 工具防越权：tool-service `execute` 统一入口业务级校验（禁止跨部门预算/跨租户查询）
- 幻觉拦截：P3 先做「存疑/不确定标记 → 强制人工复核」占位（TODO 指向 P4 评估体系）

## 3. 任务状态机扩展

```
P1:   PENDING → RUNNING → SUCCESS / FAILED
P3+:  PENDING → RUNNING → SUCCESS / FAILED / APPROVAL_PENDING / CANCELLED
                  ↑                          ↑
            提交人 resubmit 修改重跑  审批通过→SUCCESS；驳回/终止→REJECTED；撤回/撤销→CANCELLED
```

- 任务级新增 **`APPROVAL_PENDING`**（暂停等审批）、**`REJECTED`**（人工驳回，区别于系统 FAILED）
- 步骤级沿用现有状态机；命中审批触发条件时，当前流水线步骤进入暂停态

## 4. 执行点进度（P3a/P3b 已落地，P3c 待进行）

### P3a 多 Agent 角色化 + 规则流水线
- [x] **`AgentRole` 枚举 + 角色定义**：五类角色收敛于 agent-core，工具编码与角色 prompt 绑定
- [x] **`RuleBasedFlowEngine`**：REIMBURSEMENT 按固定规则流水线编排，GENERIC 保留 TaskPlanner；LLM 仅执行风控/汇总语义节点
- [x] **结果分支**：`ReviewFlowDecider` 确定性判定 `AUTO_PASS` / `NEED_REVIEW`，命中触发条件或 LLM 非 APPROVE 进入待审批
- [x] **存疑标记**：`RiskAssessment.confidence/uncertain` → 风控存疑强制 `NEED_REVIEW`（P4 再建设正式评估体系）

> P3b 已完成（v0.3，2026-08-19）：`audit_ticket`/`audit_record` 表、工单 Service/Controller、三类审批动作（approve/reject/terminate）、提交人 resubmit 修改重跑、快照留痕、撤回/撤销、可见性统一、前端审批工单页均已落地；`Redisson` 分布式锁已在 common-redis-starter 落地（工单动作并发控制）。当前边界：单级审批（`audit_level` 恒 1）、审批触发条件仍为三类确定性映射（大额/超标/风控）、WITHDRAW_PENDING 无超时（TODO 见 §8）。

### P3b 审批工单闭环
- [x] **表**：`audit_ticket` + `audit_record`（见 §5，`docs/database/migration-P3b.sql` + §4 增量：快照列 + uk_task + 新状态/动作枚举）
- [x] **工单 Service/Controller**：`AuditTicketService.enterApproval`（`NEED_REVIEW` → 生成工单，任务置 `APPROVAL_PENDING`，幂等——PENDING 返回 / AMENDED 复位 PENDING + `RERUN` 留痕；`uk_task(tenant_id, task_id)` 唯一键防同任务重复建单）+ 审批动作 approve / reject / terminate（`action()`，Redisson 锁 + 财务角色校验 + **锁内重读**；`audit_ticket.status` 新增 `WITHDRAW_PENDING`/`WITHDRAWN`）
- [x] **提交人修改重跑（resubmit，替代原财务 amend）**：`POST /reimbursements/{id}/resubmit`（PENDING/REJECTED 且仅 createdBy 本人）——全量明细拉回可改（**title/deptName 服务端强制取库内旧值、不信任请求体**），服务端 `Σitems` 重算总额（Decimal）→ 工单 `AMENDED`（rerun_count+1，上限 3 超限拒绝，**不动 auditorId**）→ `AgentTaskStepService.replan` 全量重建步骤（软删置 `deleted=id` + `uk_task_step` 含 deleted，修 TOOL 步骤陈旧 input_params 隐藏 bug，并修复重跑撞 `uk_task_step` 唯一索引——旧实现 MP 逻辑删写 1 导致同 (task_id, step_no) 重插 Duplicate entry）+ `markPlanned` 刷新 totalSteps → Controller 事务提交后 `continueTask` 重跑 → 再次分支判定（AUTO_PASS 自动闭合 `AMENDED→APPROVED` + 留痕；NEED_REVIEW 复位 PENDING **刷新 review_reasons/trigger_type/risk_desc** 再审批；重跑 FAILED → `onRerunFail` 复位 PENDING + `RERUN_FAILED` 留痕，防孤儿工单）
- [x] **撤回 / 撤销**：PENDING 直接撤回（`POST /reimbursements/{id}/withdraw` → 工单/任务/报销单 `CANCELLED` + 附件解绑 + `WITHDRAW` 留痕）；APPROVED 发起撤销（`/withdraw-request` → `WITHDRAW_PENDING`，幂等防双击 + `WITHDRAW_REQ` 留痕）→ 财务 `withdraw-agree`（作废，同上解绑，`WITHDRAW_AGREE` 留痕）或 `withdraw-refuse`（回 APPROVED，`WITHDRAW_REFUSE` 留痕）
- [x] **快照留痕**：`audit_record` 加 `before_data`/`after_data` JSON 列（`ReimbursementService.buildSnapshot`，不含 OSS 路径/预签名 URL、日期转字符串），每次动作（建单/审批/resubmit/撤回/撤销）前后可 diff；首条 SUBMIT 的 before_data 为 NULL
- [x] **可见性统一 + 角色化**：报销单/任务列表 finance 角色（`FinanceRoles.isFinance`，X-User-Roles 含 admin/auditor）看本租户全量、普通用户仅本人；规则配置菜单按角色隐藏 + 路由守卫 + 后端 `RuleController` 角色校验
- [x] **任务/单据 CANCELLED**：`TaskStatus.CANCELLED` + `ReimbursementStatus.CANCELLED` + `AgentTaskService.markCancelled`；`resume` 拒 CANCELLED（「已终结」）；`onToolResult` 对 CANCELLED/REJECTED 直接丢弃（防迟到 tool.result 把步骤标 SUCCESS 的脏状态）；`ReimbursementService.queryDuplicates` 排除 CANCELLED（作废单据发票可复用、不再当疑似重复）
- [x] **触发规则确定性映射**：`TriggerTypeResolver` 按 reason 前缀 `OVER_LIMIT > RULE_FAIL > RISK_HIT` 映射（`LLM_DECISION`/未知归 `RISK_HIT` 兜底）；大额阈值复用 P2 `finance_rule.AMOUNT_LIMIT`（TODO：发票存疑/对公/跨部门分摊扩展）。
- [x] **审计留痕**：每次动作写 `audit_record`（操作人/前后金额/意见/时间 + 前后快照），工单详情 + `GET /{id}/records` 可查完整留痕（append-only）
- [x] **前端审批工单页**：`views/audit/list.vue` + `detail.vue`（工单列表状态过滤 + 详情单据/OCR/校验异常点/留痕时间线 + 审批操作按钮 + 快照折叠展开）；**菜单/路由所有登录用户可见、审批操作按钮仅财务角色**（普通用户只读查看本人，后端 owner-read + `action()` 财务角色校验双重兜底）；轮询覆盖 `WITHDRAW_PENDING`；`views/reimbursement/detail.vue` + `edit.vue`（提交人修改重跑/撤回/发起撤销按钮 + 全量可改表单，title/dept 只读）

### P3c 安全风控
- [ ] **`PromptInjectionGuard`**（common-code）：注入检测工具（关键词/模式 + 可配置规则），agent-core 拼接 prompt 前统一校验，命中 → 任务强制人工
- [ ] **`@Mask` 注解 + 序列化切面**（common-code）：作用于对外 Controller VO 层，脱敏身份证/银行卡/税号/手机号；金额不脱敏；内部链路明文
- [ ] **tool-service `execute` 统一校验**：业务级越权（预算查询限本部门、重复检测限本租户本员工）+ 参数归属校验
- [ ] **脱敏字段清单**：随 §5 `audit_ticket`/报销单返回时应用

## 5. 新增表

**`audit_ticket` 审批工单**
| 字段 | 类型 | 说明 |
|---|---|---|
| task_id / step_no | BIGINT / INT | 关联任务 / 触发步骤（`uk_task(tenant_id, task_id)` 唯一键，同单续跑 1:1） |
| trigger_type | VARCHAR(32) | OVER_LIMIT / RULE_FAIL / RISK_HIT（重跑再次命中时刷新） |
| risk_desc | VARCHAR(512) | 风险描述（含规则命中详情；重跑再次命中时刷新） |
| origin_amount / adjusted_amount | DECIMAL(12,2) | 申报金额 / 提交人 resubmit 后总额（重跑后为最终金额） |
| status | VARCHAR(20) | PENDING / APPROVED / REJECTED / AMENDED / TERMINATED / **WITHDRAW_PENDING** / **WITHDRAWN** |
| audit_level | TINYINT | 审批级数（**预留多级审批**，P3 恒为 1，TODO） |
| auditor_id / audit_comment | BIGINT / VARCHAR(512) | 审批人 / 审批意见（提交人 resubmit **不写** auditor_id） |

**`audit_record` 审批留痕**
| 字段 | 类型 | 说明 |
|---|---|---|
| ticket_id | BIGINT | 工单 ID |
| action | VARCHAR(20) | SUBMIT / APPROVE / REJECT / AMEND / TERMINATE / RERUN / **RERUN_FAILED** / **WITHDRAW** / **WITHDRAW_REQ** / **WITHDRAW_AGREE** / **WITHDRAW_REFUSE** |
| before_amount / after_amount | DECIMAL(12,2) | 变更前后金额（resubmit 记录原金额 → Σitems 新总额） |
| before_data / after_data | JSON | 变更前后数据快照（`buildSnapshot`，不含 OSS 路径/预签名 URL；首条 SUBMIT 的 before_data 为 NULL） |
| comment | VARCHAR(512) | 操作意见 |
| operator_id / operator_name | BIGINT / VARCHAR(64) | 操作人（审计溯源，系统动作留空） |
| operator_roles | VARCHAR(128) | 操作人当时角色（留痕审计） |

## 6. 新增接口（走网关 9080，财务角色鉴权 + 租户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/audit/tickets` | 工单分页 + 状态过滤 |
| GET | `/api/v1/audit/tickets/{id}` | 工单详情（含单据 + 校验异常 + 留痕） |
| POST | `/api/v1/audit/tickets/{id}/approve` | 通过（仅 PENDING） |
| POST | `/api/v1/audit/tickets/{id}/reject` | 驳回（仅 PENDING，→ REJECTED） |
| POST | `/api/v1/audit/tickets/{id}/terminate` | 终止（仅 PENDING） |
| POST | `/api/v1/audit/tickets/{id}/withdraw-agree` | 同意撤销（仅 WITHDRAW_PENDING，→ WITHDRAWN） |
| POST | `/api/v1/audit/tickets/{id}/withdraw-refuse` | 拒绝撤销（仅 WITHDRAW_PENDING，→ APPROVED） |
| GET | `/api/v1/audit/tickets/{id}/records` | 审批留痕列表（含前后快照） |
| POST | `/api/v1/reimbursements/{id}/resubmit` | 提交人修改重跑（PENDING/REJECTED，→ 流水线重跑） |
| POST | `/api/v1/reimbursements/{id}/withdraw` | 提交人撤回（仅 PENDING，→ 作废） |
| POST | `/api/v1/reimbursements/{id}/withdraw-request` | 提交人发起撤销（仅 APPROVED，→ WITHDRAW_PENDING） |

## 7. 新增组件（代码）

| 组件 | 落点 | 说明 |
|---|---|---|
| `RuleBasedFlowEngine` | agent-core | 规则驱动流水线（顺序 + 分支），替换 TaskPlanner 的 LLM 拆解 |
| `AgentRole` + 角色定义 | agent-core | 5 角色枚举 + prompt/工具集绑定 |
| `PromptInjectionGuard` | common-code | 注入检测工具 + 可配置规则 |
| `@Mask` + 序列化切面 | common-code | 输出脱敏 |
| execute 统一越权校验 | tool-service | 工具执行入口收口校验 |

## 8. TODO 清单（P3 代码必须落注释，后续优化）

| TODO | 落点 | 目标阶段 |
|---|---|---|
| 运行中实时打断 / 修改中间结果 | RuleBasedFlowEngine + 工单模块 | P5+ |
| 多级审批链（资金类一级→二级） | `audit_ticket.audit_level` + 审批流 | P5+ |
| WITHDRAW_PENDING 超时自动回退（财务长期不处理 → 自动回 APPROVED / 通知） | 工单模块定时任务 | P4+ |
| 撤回/撤销后发票占用的正式释放语义（现为附件解绑 `reimb_id` 置 NULL） | 附件模块 | P4+ |
| 触发条件扩展（发票存疑/对公/跨部门分摊） | 触发规则配置化 | P4/P5 |
| 幻觉拦截 → 正式评估体系 | 存疑标记 → P4 幻觉检测规则 + 大盘 | P4 |
| 工具幻觉：目录为空时 LLM 虚构工具编码 | TaskPlanner 规划层校验 + RuleBasedFlowEngine 按业务绑定工具集 | **P3a 已完成** |
| 部门实体表（替代 dept_name 字符串） | P2 D7 | P5 |

## 9. 风险与预案

| 风险 | 预案 |
|---|---|
| 审批并发：同一工单多人同时操作 | **已落地**：`DistributedLockTemplate`（common-redis-starter）对 `audit:ticket:{id}` 加 Redisson 锁，锁在事务提交后释放 |
| resubmit 回退重跑死循环（修改重跑又命中触发） | **已落地**：重跑次数上限（`rerun_count` 上限 3）+ 超限拒绝强制人工；重跑失败 `onRerunFail` 复位 PENDING 防孤儿工单 |
| 脱敏误伤内部链路 | 切面仅作用于 Controller 返回层，Feign/MQ DTO 不脱敏 |
| 注入检测误报 | 规则可配置 + 命中进人工而非直接拒绝 |
| 工单跨租户泄露 | 全表 tenant_id + MP 多租户拦截器（已有） |
| WITHDRAW_PENDING 财务长期不处理 | v1 可接受（前端明确展示「撤销待审」），TODO 登记超时自动回退（§8 P4+） |

## 10. 验收清单（P3 完成定义）

1. 提交报销单 → 规则流水线自动审核（解析→预算→规则→风控）多步骤执行，步骤明细可查
2. 超标 / 大额 / 风控命中 → 任务 `APPROVAL_PENDING`，财务端工单列表可见
3. 财务 approve → 任务 SUCCESS；提交人 resubmit 修改重跑 → 结果更新（AUTO_PASS 自动闭合 / NEED_REVIEW 复位再审批）；reject → REJECTED；撤回/撤销 → 作废
4. 全部审批动作留痕（含前后数据快照），工单详情可追溯操作人/前后金额/意见/数据 diff
5. prompt 注入 payload 提交 → 被拦截并强制人工（不直接放行）
6. 输出脱敏生效：身份证/税号 masked，金额明文
7. 跨租户隔离：租户 2 财务不可见租户 1 工单
