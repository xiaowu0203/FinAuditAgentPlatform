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

