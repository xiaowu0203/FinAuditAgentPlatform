# 任务事件驱动编排（P1.3 / P3a / P3b）

> agent-core-service 与 tool-service 之间通过 RabbitMQ 事件驱动协作，任务/步骤全程落库（持久化驱动），支持失败重试与断点续跑。P3a 在此基础上为报销任务增加角色化固定流水线；P3b 增加审批工单闭环（`audit_ticket`/`audit_record` + 三类审批动作 + 提交人 resubmit 修改重跑 + 撤回/撤销 + 快照留痕 + 可见性统一）。

## 1. MQ 拓扑

交换机 `finaudit.task.exchange`（direct，持久化）。三个业务队列 + DLQ，均由 `common-mq-starter` 声明（幂等，两服务一致）。

| 队列 | routing key | 消费者 | 消息 |
|---|---|---|---|
| `finaudit.task.submit.q` | `task.submit` | agent-core | 任务已提交，触发启动 |
| `finaudit.tool.execute.q` | `tool.execute` | tool-service | 执行某工具步骤 |
| `finaudit.tool.result.q` | `tool.result` | agent-core | 工具执行结果，驱动推进 |
| `finaudit.dlq` | `dlq` | — | 失败/不可反序列化消息（死信） |

每个业务队列带死信参数：`x-dead-letter-exchange=finaudit.task.exchange`、`x-dead-letter-routing-key=dlq`，reject 消息自动进 DLQ。

## 2. 消息契约（JSON，共享 DTO 包 `com.finaudit.starter.mq.message`）

| 消息 | 字段 |
|---|---|
| `TaskSubmitMessage` | `taskId, tenantId` |
| `ToolExecuteMessage` | `taskId, stepId, tenantId, toolCode, inputParams` |
| `ToolResultMessage` | `taskId, stepId, tenantId, toolCode, result, success, errorMsg, costTimeMs` |

跨服务反序列化：`Jackson2JsonMessageConverter` + `DefaultJackson2JavaTypeMapper`，trusted packages 须精确到消息 DTO 完整包名，见 `MqTopology.MESSAGE_PACKAGE`。

## 3. 状态机（落库驱动）

任务 `agent_task.status`：`PENDING → RUNNING → SUCCESS / FAILED / APPROVAL_PENDING / REJECTED / CANCELLED`。

- `APPROVAL_PENDING` 由 P3b `AuditTicketService.enterApproval` 在结果分支 `NEED_REVIEW` 时置位，**同时生成审批工单**（`audit_ticket.status=PENDING`，含 `SUBMIT` 留痕）。
- `REJECTED` 为审批驳回/终止后的任务终态：`action(REJECT)` → `markRejected`；`action(TERMINATE)` → `markTerminated`（REJECTED + errorMsg="审批工单终止"）。
- `CANCELLED`（P3b 新增）为撤回/撤销作废后的任务终态：提交人 PENDING 撤回、财务同意撤销时 `markCancelled`；`resume` 拒绝，`onToolResult` 丢弃迟到结果。报销单侧对应新增 `ReimbursementStatus.CANCELLED`，作废单据不再当疑似重复候选、附件解绑可复用。
- 步骤 `agent_task_step.status` 仍为 `PENDING → RUNNING → SUCCESS / FAILED`；TOOL 步骤失败且 `retryCount < 3` 时回 `RUNNING` 重发 `tool.execute`。

## 4. 事件流时序

通用 `GENERIC` 任务仍由 `TaskPlanner` 进行 LLM JSON 规划；`REIMBURSEMENT` 任务由 `RuleBasedFlowEngine` 生成确定性流水线：

`DOCUMENT_PARSER(ocr_extract)` → `BUDGET_CALCULATOR(budget_query)` → `RULE_VALIDATOR(rule_check/amount_verify)` → `RISK_AUDITOR(duplicate_check + 风控语义判断)` → `SCHEDULER(结论汇总)`。

其中 OCR、预算步骤按入参条件生成，工具步骤通过 RabbitMQ 执行，LLM 仅用于风控语义判断与汇总。步骤 `agent_role` 由规则流水线绑定，LLM 不能自由指派角色。执行完成后 `ReviewFlowDecider` 根据规则、风险等级及 `confidence/uncertain` 输出 `AUTO_PASS` 或 `NEED_REVIEW`；后者写入任务结果并置 `APPROVAL_PENDING`。

```
用户 ──POST /api/v1/tasks──▶ agent-core：落库 PENDING → 发 task.submit
                                  │
  ┌─────── task.submit ──────────┘
  ▼
agent-core TaskSubmitConsumer → Orchestrator.start()
  REIMBURSEMENT → RuleBasedFlowEngine 固定流水线；GENERIC → TaskPlanner.plan()
  → 逐条落库 PENDING → continueTask()
      ├─ LLM 步骤：内联调模型 → SUCCESS → 继续下一步
      └─ TOOL 步骤：发 tool.execute → RUNNING（不阻塞）
                                  │
  ┌─────── tool.execute ──────────┘
  ▼
tool-service ToolExecuteConsumer：注册表校验 → Redis 同入参缓存 → 执行 → tool.result
                                  │
  ┌─────── tool.result ───────────┘
  ▼
agent-core ToolResultConsumer → Orchestrator.onToolResult()
  成功 → 步骤 SUCCESS → continueTask()
  失败 → retryCount<3 重发；≥3 → 步骤 FAILED → 任务 FAILED
  全部步骤 SUCCESS → 汇总 result → AUTO_PASS 或 NEED_REVIEW
```

P3b 审批工单闭环**不改变上述任务/工具 MQ 契约**，仅在结果分支接入：

```
ReviewFlowDecider 输出 AUTO_PASS ──▶ closeOnAutoPass(task)     （AMENDED 工单 → APPROVED + 留痕）
ReviewFlowDecider 输出 NEED_REVIEW ─▶ enterApproval(task, ...)  （建工单/复位 PENDING + SUBMIT/RERUN 留痕）
                                        task.status = APPROVAL_PENDING，流水线暂停

审批/撤销动作为**锁内重读**：Redisson 锁 `audit:ticket:{id}`，锁内 getRequired 重读工单再校验，动作间互斥、无丢失更新。

财务动作（X-User-Roles 含 admin/auditor）
  approve  → 工单 APPROVED；任务 markSuccess → SUCCESS；报销单 SUCCESS（仅 PENDING）
  reject   → 工单 REJECTED；任务 markRejected → REJECTED；报销单 FAILED（仅 PENDING）
  terminate→ 工单 TERMINATED；任务 markTerminated → REJECTED(errorMsg)；报销单 FAILED（仅 PENDING）
  withdraw-agree   → 工单 WITHDRAWN；任务/报销单 CANCELLED + 附件解绑（仅 WITHDRAW_PENDING）
  withdraw-refuse  → 工单回 APPROVED（仅 WITHDRAW_PENDING）

提交人动作（createdBy，锁内校验本人）
  resubmit 修改重跑（仅 PENDING/REJECTED）→ 工单 AMENDED(rerun_count+1，>3 拒绝，不动 auditorId)
              + 报销单全量覆盖（title/deptName 强制取库内旧值）+ Σitems 服务端重算
              + replan 全量重建步骤 + markPlanned 刷新 totalSteps
              → Controller 事务提交后 continueTask(taskId) 重跑 → 再次分支判定：
                  AUTO_PASS → 工单 APPROVED（留痕自动通过）
                  NEED_REVIEW → 工单复位 PENDING（刷新 review_reasons/trigger/risk_desc + RERUN 留痕）
                  重跑 FAILED → onRerunFail 复位 PENDING + RERUN_FAILED 留痕（防孤儿工单）
  withdraw 撤回（仅 PENDING）→ 工单 WITHDRAWN；任务/报销单 CANCELLED + 附件解绑 + WITHDRAW 留痕
  withdraw-request 发起撤销（仅 APPROVED）→ 工单 WITHDRAW_PENDING + WITHDRAW_REQ 留痕（幂等防双击）

每次动作追加 audit_record（操作人/前后金额/意见/时间 + before_data/after_data 快照，
`ReimbursementService.buildSnapshot` 不含 OSS 路径/预签名 URL、日期转字符串），工单详情可查完整留痕。
```

**审批工单状态机（整页图为主，mermaid 实时版 + ASCII 兜底）**

> 完整整页版（含图 + 三层联动表 + 动作矩阵 + 限流要点，带排版配色）见 `docs/images/audit-ticket-workflow.png`；下方为可内嵌实时渲染的 mermaid 版与最保守的 ASCII 骨架。

![审批工单状态机（整页，含图 + 联动表 + 动作矩阵）](./images/audit-ticket-workflow.png)

> 怎么读：从 `PENDING` 发散四条人工边；重点抓两条作废链路（左：提交人 PENDING 撤回；右下：APPROVED 后申请撤销→财务同意）与 `AMENDED` 重跑三岔（自动通过/再命中/失败，后两路都复位 PENDING）。

> 怎么读：从 `PENDING` 发散四条人工边；重点抓两条作废链路（左：提交人 PENDING 撤回；右下：APPROVED 后申请撤销→财务同意）与 `AMENDED` 重跑三岔（自动通过/再命中/失败，后两路都复位 PENDING）。

```mermaid
flowchart TB
    START([提交报销单 POST /api/v1/reimbursements])
    PIPE["自动流水线审核  OCR → 预算 → 规则 → 风控 → 汇总"]
    DECIDE{FlowDecision}
    PASS["AUTO_PASS 全合规  任务/报销单 SUCCESS · 无工单"]
    END1([结束])
    CREATE["NEED_REVIEW 命中复核  建工单 PENDING + SUBMIT 留痕"]
    PENDING["PENDING 待审批"]
    AMENDED["AMENDED 修改重跑中  rerun+1（上限3）"]
    RR{重跑结果}
    APPROVED["APPROVED 已通过  任务/报销单 SUCCESS"]
    REJECTED["REJECTED 已驳回  任务 REJECTED · 报销单 FAILED"]
    TERMINATED["TERMINATED 已终止  任务 REJECTED · 报销单 FAILED"]
    WP["WITHDRAW_PENDING 撤销待审"]
    WITHDRAWN["WITHDRAWN 已撤回/已撤销  任务/报销单 CANCELLED · 附件解绑"]
    FINAL(["终态 · 不可 resume"])

    START --> PIPE --> DECIDE
    DECIDE -- AUTO_PASS --> PASS --> END1
    DECIDE -- NEED_REVIEW --> CREATE --> PENDING
    PENDING --"提交人·撤回 withdraw 仅 PENDING"--> WITHDRAWN
    PENDING --"提交人·改明细重跑 resubmit 仅 PENDING/REJECTED"--> AMENDED
    PENDING --"财务·通过 approve"--> APPROVED
    PENDING --"财务·驳回 reject"--> REJECTED
    PENDING --"财务·终止 terminate 硬终止·不可重跑"--> TERMINATED
    AMENDED --> RR
    RR --"AUTO_PASS 自动通过"--> APPROVED
    RR --"再命中复核 RERUN 留痕"--> PENDING
    RR --"失败 onRerunFail 防死端"--> PENDING
    REJECTED --"提交人·改明细重跑 resubmit"--> AMENDED
    APPROVED --"提交人·申请撤销 withdraw-request 仅 APPROVED·幂等"--> WP
    WP --"财务·同意撤销 withdraw-agree"--> WITHDRAWN
    WP --"财务·拒绝撤销 withdraw-refuse"--> APPROVED
    APPROVED -. 终态 .-> FINAL
    REJECTED -. 终态 .-> FINAL
    TERMINATED -. 终态 .-> FINAL
    WITHDRAWN -. 终态 .-> FINAL
```

**ASCII 状态骨架（双仓兜底，Gitee 不渲染 mermaid 时阅读）**

```
                ┌─AUTO_PASS──▶ 无工单闭环（任务/报销单 SUCCESS）
提交 ─▶ 流水线 ─┤
                └─NEED_REVIEW─▶ 建工单 PENDING
                                   │　提交人 撤回 ──▶ WITHDRAWN（CANCELLED+解绑）★仅PENDING
                                   │　提交人 改明细重跑 ─▶ AMENDED（rerun+1≤3）
                                   │　　　├ AUTO_PASS ─▶ APPROVED
                                   │　　　├ 再命中复核 ─▶ PENDING（RERUN 留痕）
                                   │　　　└ 失败 ─▶ PENDING（RERUN_FAILED 防死端）
                                   │　财务 通过 ──▶ APPROVED（SUCCESS）
                                   │　财务 驳回 ──▶ REJECTED（FAILED）→提交人可重跑
                                   │　财务 终止 ──▶ TERMINATED（FAILED）★硬终止
     APPROVED ─ 提交人 申请撤销 ─▶ WITHDRAW_PENDING
                                   ├─ 财务 同意撤销 ─▶ WITHDRAWN（CANCELLED+解绑）
                                   └─ 财务 拒绝撤销 ─▶ 回 APPROVED
```

**按角色完整执行路径（业务语义走查，主文档见 `ProjectBusiness.md` §二.7）**

普通用户（createdBy，锁内校验本人）：
- `PENDING`：`withdraw` 撤回 → WITHDRAWN（任务/报销单作废 + 附件解绑）；`resubmit` 改明细重跑 → AMENDED（rerun<3）→ 三岔：重跑 AUTO_PASS → 自动 APPROVED；再命中复核 → 复位 PENDING（RERUN 留痕）；重跑 FAILED → `onRerunFail` 复位 PENDING（RERUN_FAILED 留痕，防死端）
- `REJECTED`：仅 resubmit 重跑
- `APPROVED`：`withdraw-request` 申请撤销 → WITHDRAW_PENDING（等财务决定）
- `AMENDED` / `WITHDRAW_PENDING` / `WITHDRAWN` / `TERMINATED`：只读

财务（X-User-Roles 含 admin/auditor，`allowedByStatus` 仅放行 PENDING 与 WITHDRAW_PENDING）：
- `PENDING`：approve → APPROVED / reject → REJECTED / terminate → TERMINATED
- `WITHDRAW_PENDING`：withdraw-agree → WITHDRAWN（作废 + 解绑）/ withdraw-refuse → 回 APPROVED

读权限：工单 page/detail/records 非财务按 createdBy 过滤，财务看租户全量（租户隔离由多租户拦截器保证）。

## 5. 失败重试

- 工具执行异常（业务校验失败 / 工具未启用 / 执行异常）→ `tool.result.success=false`
- agent-core 侧 `MAX_RETRY=3`：每次重试 `retryCount+1` 并重发 `tool.execute`；3 次后步骤/任务 FAILED
- 每队列消费者 `default-requeue-rejected=false`，异常消息进 DLQ，不无限重投
- 重试间隔：当前为即时重发（无退避），后续可加延迟重试

## 6. 断点续跑

`POST /api/v1/tasks/{id}/resume`：

| 任务状态 | 行为 |
|---|---|
| `PENDING` | 完整启动（规划 + 执行）——覆盖 submit 消息丢失场景 |
| `RUNNING` | 残留 `RUNNING` 步骤重置为 `PENDING`，从首个非 SUCCESS 步骤续跑——覆盖服务重启场景 |
| `SUCCESS/FAILED/APPROVAL_PENDING/REJECTED/CANCELLED` | 400 拒绝（任务已终结；待审批任务走审批工单页，resubmit 由 Controller 在事务提交后自动触发续跑，无需手动 resume） |

> 前端「续跑」按钮仅对 `PENDING`（未启动）任务展示；`RUNNING` 执行中不暴露——避免误触强制重置在途步骤并重驱流水线；卡死 RUNNING 的手动恢复暂走接口层（TODO：服务端陈旧任务检测）。

## 7. Redis 缓存

`tool:exec:{toolCode}:{SHA-256(入参JSON)}`，TTL 1h。同入参工具执行命中直接返回。
