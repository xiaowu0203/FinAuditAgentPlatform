# agent-core-service 任务 API

> 端口 9201。任务编排流程见 [`docs/architecture/task-orchestration.md`](../architecture/task-orchestration.md)。

## POST /api/v1/tasks — 提交任务

任务落库为 `PENDING`，异步经 MQ `task.submit` 触发编排：REIMBURSEMENT 走固定规则流水线（票据解析 → 预算 → 金额/规则 → 风控 → 汇总），GENERIC 仍由 TaskPlanner LLM 拆解。

请求体：

```json
{
  "title": "差旅费报销审核",
  "taskType": "REIMBURSEMENT",
  "inputParams": {
    "items": [ { "name": "高铁票", "amount": 553.00 }, { "name": "住宿费", "amount": 458.00 } ],
    "claimedTotal": 1011.00
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | ✅ | 任务标题（`@NotBlank`） |
| `taskType` | string | ❌ | 业务类型：`REIMBURSEMENT` / `GENERIC`（缺省 `GENERIC` 通用分析）。报销单走 `POST /api/v1/reimbursements` 自动标记 `REIMBURSEMENT`，规划器按业务注入财务提示词/工具，避免不同业务共用同一提示词导致规划漂移 |
| `inputParams` | object | ✅ | 任务入参，JSON 对象（`@NotNull`）；含 `items`+`claimedTotal` 时内置模板回退为「金额核验 TOOL + LLM 汇总」 |

响应（`data` 为任务对象）：

```json
{ "code": 0, "message": "ok", "data": {
  "id": 2, "tenantId": 1, "taskNo": "T202608131758424428", "title": "差旅费报销审核",
  "taskType": "REIMBURSEMENT", "inputParams": {}, "status": "PENDING", "totalSteps": 0, "finishedSteps": 0,
  "result": null, "errorMsg": null, "createdAt": "2026-08-13T18:02:29"
}}
```

## GET /api/v1/tasks/{id} — 任务详情

`data` 同提交响应；`status` 见状态机（`PENDING/RUNNING/SUCCESS/FAILED/APPROVAL_PENDING/REJECTED/CANCELLED`），`result.steps` 为各步骤执行结果汇总；P3a REIMBURSEMENT 任务的 `result` 还包含 `flowBranch`（`AUTO_PASS`/`NEED_REVIEW`）与 `reviewReasons`。`NEED_REVIEW` 会置任务 `APPROVAL_PENDING` 并生成审批工单（见下方「审批工单 API」）；审批通过（含提交人修改重跑后自动通过）→ 任务 `SUCCESS`，驳回/终止 → 任务 `REJECTED`，撤回/撤销同意 → 任务 `CANCELLED`。

## GET /api/v1/tasks/{id}/steps — 步骤明细

```json
{ "code": 0, "message": "ok", "data": [ {
  "id": 5, "stepNo": 1, "stepName": "核验各项明细金额之和与申报总额是否一致",
  "stepType": "TOOL", "toolName": "amount_verify", "agentRole": "RULE_VALIDATOR",
  "inputParams": { "items": [], "claimedTotal": 1011.00 },
  "output": { "total": 1011.00, "claimedTotal": 1011.00, "match": true, "diff": 0, "message": "金额一致" },
  "status": "SUCCESS", "errorMsg": null, "retryCount": 0
} ] }
```

`GET /api/v1/tasks/{id}/steps` 返回的 `agentRole` 标识 P3a 角色：`DOCUMENT_PARSER` 绑定 `ocr_extract`、`BUDGET_CALCULATOR` 绑定 `budget_query`、`RULE_VALIDATOR` 绑定 `rule_check/amount_verify`、`RISK_AUDITOR` 绑定 `duplicate_check`，`SCHEDULER` 负责汇总。当前仅 REIMBURSEMENT 使用固定流水线，GENERIC 仍使用 TaskPlanner。风控结果可包含 `riskLevel`、`confidence`、`uncertain`、`riskPoints`；存疑会进入 `NEED_REVIEW`。

## GET /api/v1/tasks — 分页查询`pageNum`（默认 1）、`pageSize`（默认 10）、`status`（可选，如 `SUCCESS`/`FAILED`）。可见性（P3b）：财务角色（`admin`/`auditor`）看本租户全部任务，普通用户仅见本人（`createdBy`）任务。

响应 `data` 为 MyBatis-Plus `Page`：

```json
{ "code": 0, "message": "ok", "data": {
  "total": 4, "size": 10, "current": 1, "records": [ /* TaskVO */ ]
}}
```

## POST /api/v1/tasks/{id}/resume — 断点续跑

- `PENDING`（submit 消息丢失/未启动）→ 完整启动
- `RUNNING`（含服务重启残留的 RUNNING 步骤）→ 重置残留步骤为 PENDING 后从首个非 SUCCESS 步骤续跑
- `SUCCESS/FAILED/APPROVAL_PENDING/REJECTED/CANCELLED` → 400「任务已终结，无需续跑」（P3b：含撤回/撤销作废任务，防误触发重跑）
- **前端「续跑」按钮仅对 `PENDING`（未启动）任务展示**；`RUNNING` 执行中不展示（避免误触强制重启在途流水线），卡死 RUNNING 的恢复暂走接口层（TODO：服务端陈旧任务检测）

响应：`{ "code": 0, "message": "ok", "data": null }`

## 任务/步骤状态机

| 实体 | 状态流转 |
|---|---|
| 任务 `agent_task.status` | `PENDING → RUNNING → SUCCESS / FAILED / APPROVAL_PENDING / REJECTED / CANCELLED`（`CANCELLED`：提交人撤回 / 财务同意撤销，`errorMsg` 留因） |
| 步骤 `agent_task_step.status` | `PENDING → RUNNING → SUCCESS / FAILED`（FAILED 且 `retryCount < 3` 时回到 RUNNING 重试） |

---

# 报销单 API（单据闭环，归属 agent-core）

> P2a-重构后报销 CRUD / 提交 / 任务生成 / 审核流程全部归属本服务。附件经 file-service 上传后以 `file_record` id 引用；读附件一律经 `FileServiceFeign`，禁止直连 OSS。租户经 `X-Tenant-Id`，用户经 `X-User-Id` 请求头传递（经网关由 JWT 注入）。金额服务端按明细求和（Decimal，不信任客户端）。

## POST /api/v1/reimbursements — 提交报销单（生成审核任务）

请求体（`fileRecordIds` 为 file-service 上传返回的 `file_record` id；须归属当前租户且未被其他报销单关联）：

```json
{
  "title": "出差报销", "expenseType": "TRAVEL", "deptName": "技术部",
  "claimDate": "2026-08-10", "remark": "P2a-重构 E2E 验证",
  "items": [ { "name": "高铁", "amount": 553.00, "amountType": "交通" },
             { "name": "住宿", "amount": 458.00 } ],
  "fileRecordIds": [ 1 ]
}
```

流程：费用类型校验 → `FileServiceFeign` 校验附件引用（存在 + 归属租户）→ **服务端 `computeTotal` 求和（Decimal）** → 落 `expense_reimbursement`（`status=PENDING`）→ `attachToReimb` 绑定业务附件（`file_record` 引用）→ 组任务 `input_params` 快照（reimbId/reimbNo/items/attachments:[{id:fileRecordId,fileType}]/claimedTotal，无 objectName）→ **服务内直调 `AgentTaskService.createTask` 建任务（同事务，替代跨服务 Feign，消灭孤儿任务窗口）** → 回填 `task_id`。

响应含反写后的 `taskId`：

```json
{ "code": 0, "message": "ok", "data": {
  "id": 1, "tenantId": 1, "reimbNo": "R202608151818582351", "title": "出差报销",
  "expenseType": "TRAVEL", "applicantId": 1, "deptName": "技术部",
  "totalAmount": 1011.00, "taskId": 20, "status": "PENDING",
  "claimDate": "2026-08-10", "remark": "P2a-重构 E2E 验证", "createdAt": null
} }
```

## GET /api/v1/reimbursements — 报销单分页

参数 `pageNum`/`pageSize`/`status`（可空）。可见性（P3b）：财务角色（`admin`/`auditor`，读 `X-User-Roles`）看本租户全部单据，普通用户仅本人（`X-User-Id`），租户经拦截器限定。`data` 为 `Page<ReimbursementVO>`。

## GET /api/v1/reimbursements/{id} — 报销单详情

`data` 为 `ReimbursementDetailVO`：`reimbursement`（基础信息）+ `items`（明细）+ `attachments`（附件：业务字段 + 经 `FileServiceFeign` 联取的 fileName/objectName/预签名 URL）。财务角色可查看本租户全部单据，普通用户非本人查看返回 400「无权查看他人报销单」。

## POST /api/v1/reimbursements/{id}/resubmit — 修改明细重跑（提交人）

> P3b 工作流重设计：财务不再代为修改（amend 移除），**提交人**在工单 `PENDING`/`REJECTED` 时自己改明细后同单续跑。请求体与提交报销单一致但**无 `title`/`deptName`**（服务端强制沿用库内旧值，不信任请求体）；其余字段（`expenseType`/`claimDate`/`remark`/`items`/`fileRecordIds`）可全量修改。

```json
{ "expenseType": "TRAVEL", "claimDate": "2026-08-10", "remark": "改低金额",
  "items": [ { "name": "住宿", "amount": 400.00 } ], "fileRecordIds": [ 1 ] }
```

服务端流程：附件归属校验 → `Σitems` 重算总额 → 覆盖明细字段（title/dept 保留旧值、附件移除项解绑/新增项重绑）→ 步骤全量重规划（`AgentTaskStepService.replan`，修复旧实现 TOOL 步骤 `input_params` 陈旧的隐藏 bug）→ 工单 `AMENDED`、`rerun_count+1` → 事务提交后 `continueTask` 触发重跑。重跑上限 3 次（与财务原 amend 共用计数器），超限 400。响应 `data` 为关联 `taskId`。权限：仅工单创建人；非 `PENDING`/`REJECTED` 状态 400。

## POST /api/v1/reimbursements/{id}/withdraw — 撤回（提交人）

工单 `PENDING` 时提交人直接撤回：工单 `WITHDRAWN`、任务/报销单 `CANCELLED`、附件解绑可复用（重复报销检测已排除 `CANCELLED`）。响应 `data` 为工单 VO。非本人 / 非 `PENDING` 400。

## POST /api/v1/reimbursements/{id}/withdraw-request — 发起撤销申请（提交人）

工单 `APPROVED` 时提交人发起撤销：工单 `WITHDRAW_PENDING`，等财务同意/拒绝。幂等：已 `WITHDRAW_PENDING` 直接返回当前态（防双击）。响应 `data` 为工单 VO。

## 关联

- 附件引用契约：`common-code` `FileServiceFeign`（`GET /api/v1/files` 批量 / `GET /api/v1/files/{id}/preview|download`）
- 前端任务详情跳报销单：读 `task.inputParams.reimbId`
- 网关路由：`/api/v1/reimbursements/**` → `lb://agent-core-service`

---

# 审批工单 API（P3b 闭环，归属 agent-core）

> 前缀 `/api/v1/audit/tickets`，经网关 `X-Tenant-Id`/`X-User-Id` 鉴权；审批动作要求 `X-User-Roles` 含 `admin` 或 `auditor`（**区分大小写**，种子角色小写），否则 400「无审批权限」。每次动作追加 `audit_record` 留痕（append-only），P3b 起每条留痕携带变更前后数据快照 `before_data`/`after_data`（JSON，`before_data` 首条 SUBMIT 为 null）。并发控制：`DistributedLockTemplate`（common-redis-starter）对 `audit:ticket:{id}` 加 Redisson 锁，锁在事务提交后释放；**提交人动作与财务动作共用同一把锁 + 锁内重读**（先到者改态后，后到者重读报「工单状态不允许」，无状态覆盖）。

## GET /api/v1/audit/tickets — 工单分页

参数 `pageNum`/`pageSize`/`status`（可空）/`taskId`（可空）；财务角色可见全部，普通用户仅见本人（`createdBy`）工单。`data` 为 `Page<AuditTicketVO>`：

```json
{ "code": 0, "message": "ok", "data": {
  "total": 1, "records": [ {
    "id": 1, "ticketNo": "AT-T202608171758424428", "taskId": 20, "title": "差旅费报销审核",
    "triggerType": "OVER_LIMIT", "riskDesc": "大额限额 超标",
    "originAmount": 553.00, "adjustedAmount": null, "status": "PENDING", "rerunCount": 0,
    "createdAt": "2026-08-17T18:02:29"
  } ]
}}
```

`trigger_type` 确定性映射（`TriggerTypeResolver`）：reason 前缀 `OVER_LIMIT` → 金额超限、`RULE_FAIL` → 规则校验不通过、`RISK_HIT` / `LLM_DECISION` / 未知 → 风控存疑。工单与任务 1:1（`uk_task(tenant_id, task_id)` 唯一键，同单续跑复用同一工单）。

## GET /api/v1/audit/tickets/{id} — 工单详情

`data` 为 `AuditTicketDetailVO`：`ticket`（含 `reviewReasons` 复核原因列表、`originAmount`/`adjustedAmount`、`rerunCount`）+ `reimbursement`（关联报销单详情，GENERIC 任务为 null）+ `records`（留痕）。非本人且非财务角色查看他人工单 → 400。

## GET /api/v1/audit/tickets/{id}/records — 审批留痕

`data` 为 `List<AuditRecordVO>`：`action`（SUBMIT/APPROVE/REJECT/AMEND/TERMINATE/RERUN/RERUN_FAILED/WITHDRAW/WITHDRAW_REQ/WITHDRAW_AGREE/WITHDRAW_REFUSE）、`beforeAmount`/`afterAmount`、`comment`、`operatorName`/`operatorRoles`、`beforeData`/`afterData`（JSON 快照，可展开 diff 变更前后）。

## POST /api/v1/audit/tickets/{id}/approve|reject|terminate — 财务审批动作

仅 `PENDING` 工单可操作（**P3b 移除财务 amend**，修改重跑移交提交人 `POST /reimbursements/{id}/resubmit`）。请求体可选 `{ "comment": "..." }`（reject 前端必填意见）。

| 动作 | 工单 | 任务 | 报销单 |
|---|---|---|---|
| approve | APPROVED | SUCCESS（沿用原 result） | SUCCESS |
| reject | REJECTED | REJECTED（errorMsg=驳回意见） | FAILED |
| terminate | TERMINATED | REJECTED（errorMsg=审批工单终止） | FAILED |

## POST /api/v1/audit/tickets/{id}/withdraw-agree|withdraw-refuse — 撤销审批（财务）

仅 `WITHDRAW_PENDING` 工单可操作（提交人已发起撤销申请）。agree → 工单 `WITHDRAWN`、任务/报销单 `CANCELLED`、附件解绑；refuse → 工单回 `APPROVED`（数据不动，原地返回）。请求体可选 `{ "comment": "..." }`。

## 状态机（P3b 工作流重设计）

```
ticket:  PENDING → APPROVED / REJECTED / TERMINATED / WITHDRAWN / AMENDED
              ↘ AMENDED(rerunning) →(重跑再次命中复核) PENDING       （reviewReasons/trigger 刷新 + RERUN 留痕）
                                    →(重跑 AUTO_PASS)  APPROVED      （系统留痕 comment=改金额重跑后自动通过）
                                    →(重跑 FAILED)     PENDING       （onRerunFail 复位 + RERUN_FAILED 留痕，防 AMENDED 死端）
         APPROVED → WITHDRAW_PENDING →(财务同意) WITHDRAWN
                                      →(财务拒绝) APPROVED
         PENDING → WITHDRAWN（提交人撤回，直接生效）
task:    APPROVAL_PENDING/REJECTED/RUNNING + CANCELLED（撤回/撤销同意）
reimb:   MANUAL_REVIEW/FAILED/RUNNING + CANCELLED（撤回/撤销同意）
```

| 动作 | 操作人 | 允许状态 | 效果 |
|---|---|---|---|
| resubmit 修改重跑 | 提交人 | PENDING / REJECTED | ticket→AMENDED、rerun_count+1、步骤 replan 重跑 |
| withdraw 撤回 | 提交人 | PENDING | ticket→WITHDRAWN、task/reimb→CANCELLED、附件解绑 |
| withdrawRequest 发起撤销 | 提交人 | APPROVED | ticket→WITHDRAW_PENDING（等财务） |
| withdrawAgree 同意撤销 | finance | WITHDRAW_PENDING | ticket→WITHDRAWN、task/reimb→CANCELLED、附件解绑 |
| withdrawRefuse 拒绝撤销 | finance | WITHDRAW_PENDING | ticket→APPROVED（原地返回） |
| approve / reject / terminate | finance | PENDING | 终局审批 |

`rerun_count` 全局统一（提交人 resubmit 用同一计数器，上限 3）。重跑失败（`failTask`）自动经 `onRerunFail` 复位 AMENDED 工单，不留孤儿。

## 关联

- 网关路由：`/api/v1/audit/**` → `lb://agent-core-service`（P2 已配，复用）
- 触发接入：`AgentOrchestrator.finalizeSuccess` 的 `NEED_REVIEW` 分支 → `AuditTicketService.enterApproval`（工单幂等创建/复位）；`AUTO_PASS` 分支 → `closeOnAutoPass`；`failTask` → `onRerunFail`
- 快照语义：`ReimbursementService.buildSnapshot` 生成顶层字段 + 明细 + 附件结构化引用（fileRecordId/fileType/ocrStatus），**不含 OSS 路径/预签名 URL**，日期转字符串
- 前端：`views/audit/list.vue` + `detail.vue`（**菜单/路由所有登录用户可见**；列表/详情/留痕按后端 owner-read 收窄——普通用户仅本人只读、无审批操作按钮；approve/reject/terminate/withdraw-agree|refuse 操作按钮仅财务角色展示，且后端 `AuditTicketService.action()` 强校验 `FinanceRoles.isFinance`）；提交人动作在 `views/reimbursement/detail.vue`（修改重跑/撤回/发起撤销）

---

# 规则配置 API（P2c 可视化配置 + Nacos 动态刷新）

> 归属 agent-core，前缀 `/api/v1/rules`，经网关 `X-Tenant-Id`/`X-User-Id`/`X-User-Roles` 请求头鉴权。
> 鉴权（P3b 角色化）：**财务角色优先**（`X-User-Roles` 含 `admin`/`auditor` 天然可配规则，前端菜单/路由已按角色隐藏）；其余用户回退**管理员 ID 白名单**（`finaudit.admin.user-ids`，P2 无 RBAC，P5 换完整角色校验），非白名单 403。
> 唯一约束：**同租户同 `ruleType` 仅允许一条规则**——新增/修改时业务层校验（`ensureRuleTypeUnique`）+ SQL 唯一索引 `uk_rule_type(tenant_id, rule_type, deleted)`（deleted=id 软删语义）双层兜底；冲突返回 400。
> 发布语义：`published=1` 为生效集（Nacos 快照）；`save/update/toggle` 置 `published=0`（草稿，需重新发布才生效）。
> 生效机制：发布写 Nacos `finaudit-rules-{tenantId}`，应用端经 `TenantNacosConfigHelper` 监听即时生效，**改规则不重启服务**；Nacos 无配置时降级 DB 直查（种子仍生效）。

## GET /api/v1/rules — 规则列表

`data` 为当前租户全部规则（含草稿与已发布，`RuleVO`）：

```json
{ "code": 0, "message": "ok", "data": [ {
  "id": 1, "ruleCode": "amount_limit", "ruleName": "大额报销限额", "ruleType": "AMOUNT_LIMIT",
  "ruleConfig": { "threshold": 5000 }, "enabled": 1, "published": 1, "version": "1.0",
  "createdAt": "2026-08-16T10:00:00", "updatedAt": "2026-08-16T10:00:00"
} ] }
```

`published=1` 已发布生效 / `0` 草稿待发布。

## POST /api/v1/rules — 新增规则

请求体（`ruleConfig` 按 `ruleType` 结构化；ruleCode 创建后不可改）：

```json
{ "ruleCode": "amount_limit", "ruleName": "大额报销限额", "ruleType": "AMOUNT_LIMIT",
  "ruleConfig": { "threshold": 5000 }, "enabled": 1 }
```

各类型 `ruleConfig`：

| ruleType | 结构 |
|---|---|
| `AMOUNT_LIMIT` | `{"threshold":5000}`（申报总额超过即命中） |
| `REIMBURSE_EXPIRE` | `{"maxDays":30}`（明细日期早于报销日-N天命中） |
| `TRAVEL_STANDARD` | `{"standards":[{"city":"北京","hotelDaily":500,"transportTotal":3000},...]}`（住宿均价/交通金额超标命中） |
| `SUBSIDY_LIMIT` | `{"dailyAmount":200}`（单日补贴 = subsidyAmount/hotelDays 超上限命中） |

## PUT /api/v1/rules/{id} — 修改规则

同上请求体；变更即置 `published=0`（草稿），需重新发布。`ruleConfig`/`enabled` 空值不覆盖。

## POST /api/v1/rules/{id}/toggle — 启停规则

翻转 `enabled`；变更即置 `published=0`（草稿），需重新发布。

## POST /api/v1/rules/{id}/publish — 发布规则

置目标规则 `published=1` + 版本自增，序列化**租户全部 `published=1` 规则**写 Nacos（整租户快照覆盖）。先写 Nacos 成功再落库 DB，发布失败 DB 无脏写。发布后同租户应用端立即生效（不重启）。

## 关联

- Nacos 数据源（订阅）：`spring.cloud.nacos.config.namespace=dev` + `NacosConfigManager`（`TenantNacosConfigHelper`）
- Nacos 发布（管理端）：8848 登录 → 8080 控制台 API `/v3/console/cs/config`（头 `accessToken`），配置参数见 `finaudit.nacos-config.*`
- 网关路由：`/api/v1/rules/**` → `lb://agent-core-service`

