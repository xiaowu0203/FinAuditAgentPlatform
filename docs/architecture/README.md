# 架构文档

## 文档

| 文档 | 说明 |
|---|---|
| [工程约定（conventions）](./conventions.md) | **贡献者入口**：分层与依赖 / 数据访问三大坑（JSON 列写入、关联表建模、XML 自定义 SQL）/ 多租户权威来源 / 兜底可观测性 / 验收纪律 / 提交与分支 / 「多 Agent」表述口径（P3.8 R6-7） |
| [任务事件驱动编排](./task-orchestration.md) | MQ 拓扑 / 消息契约 / 任务状态机 / **§0「多 Agent」口径澄清（单进程角色化，非 A2A）** / P3.8 声明式流水线（`FlowDefinition`）+ 收尾闸口自校验 / **P3b 审批工单状态机（mermaid+整页图+ASCII）+ 按角色完整执行路径** / 失败重试 / 断点续跑 |
| [租户鉴权与多租户隔离](./tenant-auth.md) | JWT 认证 / 网关转发头注入 / 租户上下文传播（HTTP+MQ）/ `TenantLineInnerInterceptor`（P1.4） |
| [指标口径（metrics）](./metrics.md) | **P4 量化评估的数据源口径**：成本（`model_call_log`）/ 效率（`duration_ms`）/ 风控（自动通过率、重复报销拦截率、预算超支预警、票据识别成功率）三类的字段映射 + 可直接执行的 SQL + **尚未采集的指标诚实清单**（P3.8 R9-3） |

## 待补充

总体架构图、微服务拆分图仍待补充；P3a 多 Agent 流程与 P3b 审批工单状态机已在[任务事件驱动编排](./task-orchestration.md)中说明（含审批工单流转整页图 `docs/images/audit-ticket-workflow.png`）。P3c 安全风控（Prompt 注入拦截 → 强制人工、`@Mask` 输出脱敏、工具 execute 统一越权校验）的接入点见 [P3 执行计划](../planning/P3-execution-plan.md) §4.3 与各服务 API 文档。
