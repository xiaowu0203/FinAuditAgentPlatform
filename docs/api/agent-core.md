# agent-core-service 任务 API

> 端口 9201。任务编排流程见 [`docs/architecture/task-orchestration.md`](../architecture/task-orchestration.md)。

## POST /api/v1/tasks — 提交任务

任务落库为 `PENDING`，异步经 MQ `task.submit` 触发编排（LLM 拆解 → 执行步骤 → 落库）。

请求体：

```json
{
  "title": "差旅费报销审核",
  "inputParams": {
    "items": [ { "name": "高铁票", "amount": 553.00 }, { "name": "住宿费", "amount": 458.00 } ],
    "claimedTotal": 1011.00
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | ✅ | 任务标题（`@NotBlank`） |
| `inputParams` | object | ✅ | 任务入参，JSON 对象（`@NotNull`）；含 `items`+`claimedTotal` 时内置模板回退为「金额核验 TOOL + LLM 汇总」 |

响应（`data` 为任务对象）：

```json
{ "code": 0, "message": "ok", "data": {
  "id": 2, "tenantId": 1, "taskNo": "T202608131758424428", "title": "差旅费报销审核",
  "inputParams": {}, "status": "PENDING", "totalSteps": 0, "finishedSteps": 0,
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
