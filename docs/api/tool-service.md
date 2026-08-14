# tool-service 工具 API

> 端口 9202。工具注册表 + 执行器；agent-core 经 MQ `tool.execute` / `tool.result` 联动（无需走本 API），本组接口用于管理/调试。

## GET /api/v1/tools — 已启用工具列表

`data` 为 `tool_registry` 列表（仅 `enabled=1`）：

```json
{ "code": 0, "message": "ok", "data": [ {
  "id": 1, "tenantId": 1, "toolCode": "amount_verify", "toolName": "金额核验工具",
  "description": "加总明细金额并与申报总额比对，返回是否一致及差额。",
  "inputSchema": { "type": "object", "properties": {}, "required": ["items", "claimedTotal"] },
  "enabled": 1, "version": "1.0"
} ] }
```

## POST /api/v1/tools — 注册工具

按 `tenantId + toolCode` upsert。请求体：

```json
{
  "toolCode": "amount_verify",
  "toolName": "金额核验工具",
  "description": "加总明细金额并与申报总额比对",
  "inputSchema": { "type": "object" },
  "enabled": 1,
  "version": "1.0"
}
```

`enabled` / `version` 缺省时默认 `1` / `1.0`。

## POST /api/v1/tools/{code}/execute — 调试直调

绕过 MQ 直接执行工具（联调/排障用）。请求体：

```json
{ "inputParams": { "items": [ { "name": "餐费", "amount": 88.00 } ], "claimedTotal": 88.00 } }
```

`data` 为工具结果；入参非法时返回 400（如缺 `amount` →「明细缺少 amount」）。

## 内置工具

| toolCode | 说明 | 入参 | 结果 |
|---|---|---|---|
| `amount_verify` | 金额核验（全程 BigDecimal） | `{items:[{name,amount}], claimedTotal}` | `{total, claimedTotal, match, diff, message}` |
