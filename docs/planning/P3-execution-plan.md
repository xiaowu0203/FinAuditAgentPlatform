# P3 多 Agent 协同与审批工单 · 执行点文档

> 版本: v0.1 ｜ 状态: **P3a 已完成，P3b/P3c 待实施** ｜ 前置依赖: P2 完成
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

**决策3 安全风控**
- 注入拦截：落 **agent-core**（prompt 拼接前 `PromptInjectionGuard`），网关不拦
- 输出脱敏：后端统一 `@Mask` 注解 + 序列化切面（对外 VO 层），脱敏身份证/银行卡/税号/手机号，**金额不脱敏**；内部 Feign/MQ 保持明文
- 工具防越权：tool-service `execute` 统一入口业务级校验（禁止跨部门预算/跨租户查询）
- 幻觉拦截：P3 先做「存疑/不确定标记 → 强制人工复核」占位（TODO 指向 P4 评估体系）

## 3. 任务状态机扩展

```
P1:   PENDING → RUNNING → SUCCESS / FAILED
P3+:  PENDING → RUNNING → SUCCESS / FAILED / APPROVAL_PENDING
                  ↑                          ↑
            改金额回退重跑        审批通过→SUCCESS；驳回/终止→REJECTED
```

- 任务级新增 **`APPROVAL_PENDING`**（暂停等审批）、**`REJECTED`**（人工驳回，区别于系统 FAILED）
- 步骤级沿用现有状态机；命中审批触发条件时，当前流水线步骤进入暂停态

## 4. 执行点进度（P3a 已落地，P3b/P3c 待进行）

### P3a 多 Agent 角色化 + 规则流水线
- [x] **`AgentRole` 枚举 + 角色定义**：五类角色收敛于 agent-core，工具编码与角色 prompt 绑定
- [x] **`RuleBasedFlowEngine`**：REIMBURSEMENT 按固定规则流水线编排，GENERIC 保留 TaskPlanner；LLM 仅执行风控/汇总语义节点
- [x] **结果分支**：`ReviewFlowDecider` 确定性判定 `AUTO_PASS` / `NEED_REVIEW`，命中触发条件或 LLM 非 APPROVE 进入待审批
- [x] **存疑标记**：`RiskAssessment.confidence/uncertain` → 风控存疑强制 `NEED_REVIEW`（P4 再建设正式评估体系）

> P3a 当前边界：仅将任务标记为 `APPROVAL_PENDING` 并在任务结果中展示复核原因；`audit_ticket` 工单、审批动作和 `audit_record` 留痕均属于尚未实施的 P3b。

### P3b 审批工单闭环
- [ ] **表**：`audit_ticket` + `audit_record`（见 §5）
- [ ] **工单 Service/Controller**：创建工单（`NEED_REVIEW` → 生成 ticket，任务置 `APPROVAL_PENDING`）+ 审批动作 approve / reject / amend / terminate
- [ ] **amend 回退重跑**：`adjusted_amount` 写回任务入参 → 流水线重跑（回 RUNNING）→ 结果重新落库 → 再次分支判定
- [ ] **触发规则配置化**：P3a 已完成确定性 `AUTO_PASS/NEED_REVIEW` 分支；大额阈值 / 超标 / 风控命中转为工单触发规则属于 P3b，复用 P2 `finance_rule` 体系，不写死（TODO：发票存疑/对公/跨部门分摊扩展）。
- [ ] **审计留痕**：每次审批动作写 `audit_record`（操作人/前后金额/意见/时间），工单详情可查完整留痕
- [ ] **前端审批工单页**：工单列表（状态过滤）+ 详情（单据 + OCR + 校验异常点 + 留痕）+ 审批操作按钮；财务角色可见

### P3c 安全风控
- [ ] **`PromptInjectionGuard`**（common-code）：注入检测工具（关键词/模式 + 可配置规则），agent-core 拼接 prompt 前统一校验，命中 → 任务强制人工
- [ ] **`@Mask` 注解 + 序列化切面**（common-code）：作用于对外 Controller VO 层，脱敏身份证/银行卡/税号/手机号；金额不脱敏；内部链路明文
- [ ] **tool-service `execute` 统一校验**：业务级越权（预算查询限本部门、重复检测限本租户本员工）+ 参数归属校验
- [ ] **脱敏字段清单**：随 §5 `audit_ticket`/报销单返回时应用

## 5. 新增表

**`audit_ticket` 审批工单**
| 字段 | 类型 | 说明 |
|---|---|---|
| task_id / step_no | BIGINT / INT | 关联任务 / 触发步骤 |
| trigger_type | VARCHAR(32) | OVER_LIMIT / RULE_FAIL / RISK_HIT |
| risk_desc | VARCHAR(512) | 风险描述（含规则命中详情） |
| origin_amount / adjusted_amount | DECIMAL(12,2) | 申报金额 / 财务修改后金额 |
| status | VARCHAR(20) | PENDING / APPROVED / REJECTED / AMENDED / TERMINATED |
| audit_level | TINYINT | 审批级数（**预留多级审批**，P3 恒为 1，TODO） |
| auditor_id / audit_comment | BIGINT / VARCHAR(512) | 审批人 / 审批意见 |

**`audit_record` 审批留痕**
| 字段 | 类型 | 说明 |
|---|---|---|
| ticket_id | BIGINT | 工单 ID |
| action | VARCHAR(16) | APPROVE / REJECT / AMEND / TERMINATE / COMMENT |
| before_amount / after_amount | DECIMAL(12,2) | 变更前后金额 |
| comment | VARCHAR(512) | 操作意见 |
| operator_id / operator_name | BIGINT / VARCHAR | 操作人（审计溯源） |

## 6. 新增接口（走网关 9080，财务角色鉴权 + 租户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/audit/tickets` | 工单分页 + 状态过滤 |
| GET | `/api/v1/audit/tickets/{id}` | 工单详情（含单据 + 校验异常 + 留痕） |
| POST | `/api/v1/audit/tickets/{id}/approve` | 通过 |
| POST | `/api/v1/audit/tickets/{id}/reject` | 驳回（→ REJECTED） |
| POST | `/api/v1/audit/tickets/{id}/amend` | 改金额（→ 回退流水线重跑） |
| POST | `/api/v1/audit/tickets/{id}/terminate` | 终止 |
| GET | `/api/v1/audit/tickets/{id}/records` | 审批留痕列表 |

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
| 触发条件扩展（发票存疑/对公/跨部门分摊） | 触发规则配置化 | P4/P5 |
| 幻觉拦截 → 正式评估体系 | 存疑标记 → P4 幻觉检测规则 + 大盘 | P4 |
| 工具幻觉：目录为空时 LLM 虚构工具编码 | TaskPlanner 规划层校验 + RuleBasedFlowEngine 按业务绑定工具集 | **P3a 已完成** |
| 部门实体表（替代 dept_name 字符串） | P2 D7 | P5 |

## 9. 风险与预案

| 风险 | 预案 |
|---|---|
| 审批并发：同一工单多人同时操作 | 工单 `status` 乐观锁 / Redisson 分布式锁（P3 引入 Redisson） |
| amend 回退重跑死循环（改金额又命中触发） | 重跑次数上限（`rerun_count`）+ 超限强制人工 |
| 脱敏误伤内部链路 | 切面仅作用于 Controller 返回层，Feign/MQ DTO 不脱敏 |
| 注入检测误报 | 规则可配置 + 命中进人工而非直接拒绝 |
| 工单跨租户泄露 | 全表 tenant_id + MP 多租户拦截器（已有） |

## 10. 验收清单（P3 完成定义）

1. 提交报销单 → 规则流水线自动审核（解析→预算→规则→风控）多步骤执行，步骤明细可查
2. 超标 / 大额 / 风控命中 → 任务 `APPROVAL_PENDING`，财务端工单列表可见
3. 财务 approve → 任务 SUCCESS；amend 改金额 → 回退重跑 → 结果更新；reject → REJECTED
4. 全部审批动作留痕，工单详情可追溯操作人/前后金额/意见
5. prompt 注入 payload 提交 → 被拦截并强制人工（不直接放行）
6. 输出脱敏生效：身份证/税号 masked，金额明文
7. 跨租户隔离：租户 2 财务不可见租户 1 工单
