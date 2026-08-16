# agent-core-service 任务 API

> 端口 9201。任务编排流程见 [`docs/architecture/task-orchestration.md`](../architecture/task-orchestration.md)。

## POST /api/v1/tasks — 提交任务

任务落库为 `PENDING`，异步经 MQ `task.submit` 触发编排（LLM 拆解 → 执行步骤 → 落库）。

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

`data` 同提交响应；`status` 见状态机（`PENDING/RUNNING/SUCCESS/FAILED`），`result.steps` 为各步骤执行结果汇总。

## GET /api/v1/tasks/{id}/steps — 步骤明细

```json
{ "code": 0, "message": "ok", "data": [ {
  "id": 5, "stepNo": 1, "stepName": "核验各项明细金额之和与申报总额是否一致",
  "stepType": "TOOL", "toolName": "amount_verify",
  "inputParams": { "items": [], "claimedTotal": 1011.00 },
  "output": { "total": 1011.00, "claimedTotal": 1011.00, "match": true, "diff": 0, "message": "金额一致" },
  "status": "SUCCESS", "errorMsg": null, "retryCount": 0
} ] }
```

## GET /api/v1/tasks — 分页查询

Query 参数：`pageNum`（默认 1）、`pageSize`（默认 10）、`status`（可选，如 `SUCCESS`/`FAILED`）。

响应 `data` 为 MyBatis-Plus `Page`：

```json
{ "code": 0, "message": "ok", "data": {
  "total": 4, "size": 10, "current": 1, "records": [ /* TaskVO */ ]
}}
```

## POST /api/v1/tasks/{id}/resume — 断点续跑

- `PENDING`（submit 消息丢失/未启动）→ 完整启动
- `RUNNING`（含服务重启残留的 RUNNING 步骤）→ 重置残留步骤为 PENDING 后从首个非 SUCCESS 步骤续跑
- `SUCCESS/FAILED` → 400「任务已终结，无需续跑」

响应：`{ "code": 0, "message": "ok", "data": null }`

## 任务/步骤状态机

| 实体 | 状态流转 |
|---|---|
| 任务 `agent_task.status` | `PENDING → RUNNING → SUCCESS / FAILED` |
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

参数 `pageNum`/`pageSize`/`status`（可空）；**仅本人**（按 `X-User-Id` 过滤），租户经拦截器限定。`data` 为 `Page<ReimbursementVO>`。

## GET /api/v1/reimbursements/{id} — 报销单详情

`data` 为 `ReimbursementDetailVO`：`reimbursement`（基础信息）+ `items`（明细）+ `attachments`（附件：业务字段 + 经 `FileServiceFeign` 联取的 fileName/objectName/预签名 URL）。非本人查看返回 400「无权查看他人报销单」。

## 关联

- 附件引用契约：`common-code` `FileServiceFeign`（`GET /api/v1/files` 批量 / `GET /api/v1/files/{id}/preview|download`）
- 前端任务详情跳报销单：读 `task.inputParams.reimbId`
- 网关路由：`/api/v1/reimbursements/**` → `lb://agent-core-service`

---

# 规则配置 API（P2c 可视化配置 + Nacos 动态刷新）

> 归属 agent-core，前缀 `/api/v1/rules`，经网关 `X-Tenant-Id`/`X-User-Id` 请求头鉴权。
> 鉴权：**管理员 ID 白名单**（`finaudit.admin.user-ids`，P2 无 RBAC，P5 换角色校验），非白名单 403。
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

