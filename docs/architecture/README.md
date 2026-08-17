# 架构文档

## 文档

| 文档 | 说明 |
|---|---|
| [任务事件驱动编排](./task-orchestration.md) | MQ 拓扑 / 消息契约 / P1 任务状态机 / P3a 报销固定流水线与角色化步骤 / 失败重试 / 断点续跑 |
| [租户鉴权与多租户隔离](./tenant-auth.md) | JWT 认证 / 网关转发头注入 / 租户上下文传播（HTTP+MQ）/ `TenantLineInnerInterceptor`（P1.4） |

## 待补充

总体架构图、微服务拆分图仍待补充；P3a 多 Agent 流程已在[任务事件驱动编排](./task-orchestration.md)中说明，P3b 审批流时序图待审批工单实现后补充。
