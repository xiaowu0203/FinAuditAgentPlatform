# 架构文档

## 文档

| 文档 | 说明 |
|---|---|
| [任务事件驱动编排](./task-orchestration.md) | MQ 拓扑 / 消息契约 / 任务状态机 / P3a 报销固定流水线与角色化步骤 / **P3b 审批工单状态机（mermaid+整页图+ASCII）+ 按角色完整执行路径** / 失败重试 / 断点续跑 |
| [租户鉴权与多租户隔离](./tenant-auth.md) | JWT 认证 / 网关转发头注入 / 租户上下文传播（HTTP+MQ）/ `TenantLineInnerInterceptor`（P1.4） |

## 待补充

总体架构图、微服务拆分图仍待补充；P3a 多 Agent 流程与 P3b 审批工单状态机已在[任务事件驱动编排](./task-orchestration.md)中说明（含审批工单流转整页图 `docs/images/audit-ticket-workflow.png`）。
