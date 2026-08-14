# 接口文档

> 统一返回结构 `R<T>` 与各服务接口说明。接口均为 JSON；网关 P1.4 就绪后统一走 9080，当前联调直连服务端口。

## 统一返回结构 R&lt;T&gt;

```json
{ "code": 0, "message": "ok", "data": {}, "timestamp": "2026-08-13T18:02:33" }
```

| 字段 | 说明 |
|---|---|
| `code` | 0 成功；非 0 失败（400 参数/业务校验、500 系统异常） |
| `message` | 提示信息 |
| `data` | 业务数据（可能为 null） |
| `timestamp` | 服务端时间 |

## 通用约定

- 租户来源：请求头 `X-Tenant-Id`（P1.4 前默认 `1`；P1.4 网关从 JWT 注入）
- 错误：由 `common-code` 全局异常处理器统一包装为 `R<T>`，业务校验抛 `BizException`

## 服务接口

| 服务 | 端口 | 文档 |
|---|---|---|
| `agent-core-service` | 9201 | [任务 API（提交/详情/步骤/分页/续跑）](./agent-core.md) |
| `tool-service` | 9202 | [工具 API（列表/注册/调试直调）](./tool-service.md) |
| `agent-gateway` | 9080 | P1.4 后补 |
| `tenant-service` | 待定 | P1.4 后补 |
