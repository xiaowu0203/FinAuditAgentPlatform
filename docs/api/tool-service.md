# tool-service 工具 API

> 端口 9202。工具注册表 + 执行器；agent-core 经 MQ `tool.execute` / `tool.result` 联动（无需走本 API），本组接口用于管理/调试。
> 全部端点均需经网关（9080）访问：`X-Tenant-Id` **缺失即拒绝**（fail-closed，不再回退默认租户 1）。
> 返回恒为 HTTP 200，业务结果在 body 的 `R<T>`（错误码见 `code`）——语义见 [`README.md`](./README.md) 第「统一返回结构」。

## 端点与权限码（P3.5 / P3.8 R0-7）

| 方法 | 路径 | 权限码（`@RequirePerm`） | 说明 |
|---|---|---|---|
| GET | `/api/v1/tools` | `tool:manage` | 已启用工具列表（本租户） |
| POST | `/api/v1/tools` | `tool:manage` | 注册/更新工具（按 `tenantId + toolCode` upsert） |
| POST | `/api/v1/tools/{code}/execute` | `tool:execute` | 调试直调（绕过 MQ） |
| GET | `/internal/tools` | 无（网关不路由） | 内部契约：供 agent-core `TaskPlanner` 按租户拉工具目录 |

> 权限码由 common-code `PermissionInterceptor` 校验（`PermissionInterceptor.java:53-70`），
> 值取网关注入的 `X-User-Perms`（Redis 权限快照为权威，缺失时 fail-closed）。
> 「无用户上下文」与「无权限码」是两回事：`/internal/tools` 无用户上下文、**故意不挂权限码**，
> 靠「网关不配路由」做结构性隔离；而 `GET /api/v1/tools` 原先同时承担对外目录与内部 Feign 两种职责，
> 挂权限码会让内部链路 403，故拆出 `InternalToolController`（`InternalToolController.java:16-29`）。

## GET /api/v1/tools — 已启用工具列表

`data` 为 `tool_registry` 列表（仅 `enabled=1`）：

```json
{ "code": 0, "message": "ok", "data": [ {
  "id": 1, "tenantId": 1, "toolCode": "amount_verify", "toolName": "金额核验工具",
  "description": "加总明细金额并与申报总额比对，返回是否一致及差额。…",
  "inputSchema": { "type": "object", "properties": { "items": { "type": "array" }, "claimedTotal": { "type": "number" } }, "required": ["items", "claimedTotal"] },
  "outputSchema": { "type": "object", "properties": { "total": { "type": "number" }, "claimedTotal": { "type": "number" }, "match": { "type": "boolean" }, "diff": { "type": "number" }, "message": { "type": "string" } }, "required": ["total", "claimedTotal", "match"] },
  "enabled": 1, "version": "1.0", "scenario": "FINANCE", "cacheable": 1
} ], "timestamp": "2026-08-13T18:02:33" }
```

`enabled` 为 `ToolEnabledStatus` 枚举，序列化输出**数字**（1 启用 / 0 禁用，靠 `@JsonValue` 标注在值字段上，故 JSON 里是 `1`/`0` 而非 `"ENABLED"`；`ToolEnabledStatus.java:18-20`）。

## POST /api/v1/tools — 注册工具

按 `tenantId + toolCode` upsert（`ToolRegistryService.register`，`ToolRegistry.java:84-123`）。请求体：

```json
{
  "toolCode": "amount_verify",
  "toolName": "金额核验工具",
  "description": "加总明细金额并与申报总额比对",
  "inputSchema": { "type": "object", "properties": { "items": { "type": "array" }, "claimedTotal": { "type": "number" } }, "required": ["items", "claimedTotal"] },
  "outputSchema": { "type": "object", "properties": { "total": { "type": "number" } } },
  "enabled": 1,
  "version": "1.0",
  "scenario": "FINANCE",
  "cacheable": 0
}
```

| 字段 | 必填 | 缺省 | 说明 |
|---|---|---|---|
| `toolCode` | ✅ | — | 必须是 `ToolCode` 枚举已实现的编码，否则拒绝（`ToolRegistryService.java:58`） |
| `toolName` | ✅ | — | 工具名称 |
| `description` | ❌ | — | 工具描述 |
| `inputSchema` | ✅ | — | 入参 JSON Schema；注册即校验**合法性 + 强度**（见下） |
| `outputSchema` | ❌ | null | 出参 JSON Schema；非空则执行后校验出参形状；**更新时仅在显式给定时覆盖**（不误清空既有契约） |
| `enabled` | ❌ | `1` | 1 启用 / 0 禁用 |
| `version` | ❌ | `"1.0"` | 工具版本 |
| `scenario` | ❌ | `"FINANCE"` | 业务场景 `FINANCE`/`GENERIC`（`TaskPlanner` 按此收敛工具目录）；空值不覆盖、走 DB 默认列值 |
| `cacheable` | ❌ | `1` | 结果缓存开关；**有状态查询工具应显式传 `0`**（种子里的 4 个审核工具均为 0） |

**Schema 强校验（P3.8 R6-4，`ToolRegistryService.validateSchemaDefinition`）**：入参/出参 Schema 必须能被 JSON Schema(V7) 解析、必须声明 `type: object`，且入参 Schema 的 `properties` 不能为空（空属性等于没有校验）。

## POST /api/v1/tools/{code}/execute — 调试直调

绕过 MQ 直接执行工具（联调/排障用）。请求体：

```json
{ "inputParams": { "items": [ { "name": "餐费", "amount": 88.00 } ], "claimedTotal": 88.00 } }
```

`data` 为工具结果 Map。成功示例：

```json
{ "code": 0, "message": "ok", "data": {
  "total": 88.00, "claimedTotal": 88.00, "match": true, "diff": 0.00, "message": "金额一致"
}, "timestamp": "2026-08-13T18:02:33" }
```

失败示例（缺明细金额）——**HTTP 仍为 200**，业务错误码在 body：

```json
{ "code": 400, "message": "明细缺少 amount: {name=餐费}", "data": null, "timestamp": "2026-08-13T18:02:33" }
```

常见 `code=400` 文案（均为 `BizException` 默认码 400）：`工具未注册或已禁用: {code}`、`工具未实现执行器: {code}`、`工具入参校验失败[{code}]: …`、`工具出参校验失败[{code}]: …`、`缺少租户标识 X-Tenant-Id，请通过网关访问`、`缺少登录上下文（权威租户不可信），请通过网关携带 JWT 访问`。
权限不足由拦截器直接返回 **HTTP 403** + `{"code":403,"message":"无权限访问"}`（`PermissionInterceptor.writeForbidden`）。

## 工具执行链（P3c；P3.8 R6-2/R6-4 补全）

`ToolRegistryService.execute`（`ToolRegistryService.java:129-154`）按**固定顺序**串起五道关卡，HTTP 调试直调与 MQ `tool.execute` 两条链路共用：

| 序 | 关卡 | 落点 | 行为 |
|---|---|---|---|
| 1 | 编码解析 | `ToolCode.of` | 非枚举编码直接业务报错（`ToolCode` 为唯一真相） |
| 2 | 注册表校验 | `findByCode` | 未注册或 `enabled!=1` → `工具未注册或已禁用` |
| 3 | **入参 Schema 校验** | `validateInput` | `input_schema` 非空即强校验 `inputParams`；非法入参在进入执行器前拦截 |
| 4 | **防越权守卫** | `ToolAccessGuard.check` | 权威租户一致性 / 任务归属（MQ）/ 部门归属 / 单据归属，见下表 |
| 5 | 执行器分发 | `executorMap` → `executor.execute` | 按 `ToolCode` 取唯一实现 |
| 6 | **出参 Schema 校验** | `validateOutput` | `output_schema` 非空即校验执行器返回值；无 schema 跳过（兼容存量工具） |

**防越权守卫（`ToolAccessGuard.java:45-55`）**

| 校验 | 工具 | 行为 |
|---|---|---|
| 权威租户一致性 | 全部 | 权威租户（`ToolTenantCredential.authTenantId`）与声明租户**来源分离**后比对：HTTP 链路权威租户取网关/JWT 派生的 `UserContextHolder`（缺失即由 `ToolController.requireAuthTenant` 拒绝，fail-closed）；MQ 链路无 JWT，改由 `taskId` 经 agent-core 反查**任务真实归属租户**与消息声明比对；两者皆无（内部/单测直调）降级不阻断 + trace 日志 |
| 部门校验 | `budget_query` | `deptName` 与 `deptId` **二者任一**即可，两层都缺才拒绝；有凭证（`deptId`/`reimbId`）→ 经 agent-core `GET /internal/audit/budgets/allowed` 校验「预算行 `dept_id` == 报销单 `dept_id`」且部门为真实 `sys_dept`，不通过即**拒绝**；无凭证（存量任务/直调）→ 仅告警留痕不阻断 |
| 单据归属 | `duplicate_check` / `ocr_extract` / `invoice_match` | 入参 `reimbId` 不存在或非本租户 → 拒绝（经 agent-core 反查 `reimbId` 归属租户） |

> 历史说明：P3c 首版的「租户一致性」用 `TenantContextHolder` 与声明租户比对，而两条链路上两者**同源**（MQ 消费者用消息租户设上下文、HTTP 两者同取 `X-Tenant-Id`），故在生产路径**恒等通过**；P3.8 R6-2 把权威租户与声明租户拆成显式入参后才真正可触发。

## 内置工具

| toolCode | 说明 | 入参 | 结果 |
|---|---|---|---|
| `amount_verify` | 金额核验（全程 BigDecimal，保留 2 位） | `{items:[{name,amount}], claimedTotal}` | `{total, claimedTotal, match, diff, message}` |
| `ocr_extract` | 票据 OCR 识别（失败自动重试 ≤3 后转人工录入 `status=FAILED`） | `{reimbId, attachmentIds:[fileRecordId]}` | `{reimbId, receipts:[…], successCount, failedCount, message}` |
| `budget_query` | 预算核算（查部门当月剩余预算；P3.5b 起 `deptId` 为权威键） | `{deptName, deptId, reimbId, claimDate, amount}`（`deptName`/`deptId` 二选一） | 已配置：`{configured:true, deptName, period, totalBudget, usedAmount, remaining, claimedAmount, exceedsBudget, message}`；未配置：`{configured:false, message, exceedsBudget:false}` |
| `rule_check` | 财务规则校验（P2c 起规则源为 Nacos 动态刷新快照，无配置降级 DB） | `{expenseType, claimDate, totalAmount, items:[{name,amount,date,city,hotelDays,hotelAmount,transportAmount,subsidyAmount}]}`（差旅/补贴评估字段，缺失自动跳过） | `{hits:[{ruleCode,ruleName,ruleType,message,overLimit,itemIndex,itemName,expected,actual}], overLimit, message}` |
| `duplicate_check` | 重复报销检测（P3.8 R3 两级判据：发票号硬命中 = HIGH；金额+商户+日期±30 天近似 = MEDIUM） | `{reimbId}` | `{dupLevel, suspectedHigh, suspected, duplicates:[…], message}` |
| `invoice_match` | 票据-明细交叉核验（P3.8 R3-3/R3-5，含离线规则验真） | `{reimbId, items:[{name,amount}], claimedTotal?}` | `{match, invoiceCount, itemCount, invoiceTotal, claimTotal, gap, flags:[{code,message}], message}` |

### `ocr_extract` 结果形状（P3.8 D-1 修正）

`receipts[]` 每项由 `OcrExtractTool.extractOne` 组装，**成功与失败形状不同**
（`OcrExtractTool.java:208-215` / `:247-251`）：

```json
{
  "reimbId": 12,
  "receipts": [
    { "fileRecordId": 101, "status": "SUCCESS", "receiptType": "vat_invoice", "fileType": "INVOICE",
      "fields": { "receiptType": "vat_invoice", "amount": 100.00, "date": "2025年04月02日",
                  "merchant": "某某科技有限公司", "taxNo": "91110…", "invoiceCode": null,
                  "invoiceNum": "07632553", "ocrDate": "2025-04-02" } },
    { "fileRecordId": 102, "status": "FAILED", "message": "未识别到票据，需人工录入" }
  ],
  "successCount": 1,
  "failedCount": 1,
  "message": "票据识别完成，成功 1 张，失败 1 张（需人工录入）"
}
```

- 字段在 `receipts[].fields` 内，**不是**顶层 `amount/date/merchant`；
- `status` 取值 `SUCCESS` / `FAILED`；
- `fields.date` 是 OCR 原样展示串（可能为「2025年04月02日」中文格式），结构化日期在 `fields.ocrDate`（`yyyy-MM-dd`）；
- `status=FAILED` 的项**没有** `fields`，只有 `message`；失败状态同样回写 `ocr_status=FAILED` 供人工录入；
- 失败项不计为工具异常——`successCount`/`failedCount` 只是统计，整体仍返回 `code=0`。

### `duplicate_check` 结果形状（P3.8 D-2 修正）

`suspected` 是**布尔**（保留兼容既有调用方），疑似清单在 `duplicates[]`；权威分级字段是 `dupLevel`：

```json
{
  "dupLevel": "LEVEL_HIGH",
  "suspectedHigh": true,
  "suspected": true,
  "duplicates": [
    { "reimbId": 57, "reimbNo": "BX20260801001", "title": "差旅报销", "totalAmount": 1000.00,
      "claimDate": "2026-08-01", "merchant": "某某酒店", "merchantMatched": true,
      "dupLevel": "LEVEL_HIGH", "invoiceCode": null, "invoiceNum": "07632553" }
  ],
  "message": "发现 1 条疑似重复报销，其中含发票号硬命中（同一张票已报销），需人工复核"
}
```

- `dupLevel`：`NONE` / `LEVEL_MEDIUM` / `LEVEL_HIGH`（`DuplicateCheckTool.java:65-76`）；
- `suspectedHigh` = 发票号硬命中（`LEVEL_HIGH`），风控应**仅据此**触发；`LEVEL_MEDIUM` 只作展示不阻断；
- `duplicates[].invoiceCode`/`invoiceNum` 仅 HIGH 级命中才有值。

### `budget_query` 结果形状（P3.8 D-3 修正）

已配置预算时（`BudgetQueryTool.java:85-97`）：

```json
{
  "configured": true, "deptName": "研发部", "period": "2026-08",
  "totalBudget": 100000.00, "usedAmount": 32000.00, "remaining": 68000.00,
  "claimedAmount": 70000.00, "exceedsBudget": true,
  "message": "申报金额 70000 超出部门剩余预算 68000"
}
```

**未配置预算时只返回三个键、没有 `remaining`**（`BudgetQueryTool.java:73-78`）：

```json
{ "configured": false, "message": "部门[研发部] 2026-08 未配置预算", "exceedsBudget": false }
```

> ⚠️ 消费方必须先判 `configured`：未配置时读 `remaining` 会得到 `undefined`/null。
> 未配置预算不阻断业务（存量种子只覆盖 4 个部门 1 个周期）。
