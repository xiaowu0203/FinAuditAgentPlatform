# Future Roadmap · 全项目 TODO 归集

> 归集散落在各计划/需求文档与代码注释中的 **TODO / 后置 / 预留 / 待定** 事项，作为跨阶段优化清单的**单一真相**。
> 约定：P2/P3 代码落 TODO 注释时，同步在本表登记/更新；来源列指向原文档位置。
> 目标阶段对应分期：P2 单据闭环与审核工具 → P3 多 Agent 与审批工单 → P4 可观测评估 → P5 开源打磨。

## 1. 已决策归档（P2 决策，已定结论）

| 待办 | 说明 | 来源 | 状态 |
|---|---|---|---|
| D4 文件能力载体 | **已定：新增 `file-service`（9205）承载纯文件资源**（唯一持有 `common-oss-starter`；报销域迁 agent-core 服务内直调建任务，读附件走 `FileServiceFeign`；rag-service 回归 RAG 专用空骨架，P4 填 Milvus） | P2-execution-plan §3 D4 | 已定（P2a 落地） |
| D5 OCR 厂商选型 | **已定：百度 + `common-ocr-starter`**；AK/SK 走 `FINAUDIT_OCR_BAIDU_API_KEY` / `FINAUDIT_OCR_BAIDU_SECRET_KEY` | P2-execution-plan §3 D6 | 已定（P2b） |
| D7 部门实体表 | 先 `dept_name` 字符串，独立部门表后置 | P2-execution-plan §3 D7 / P3 §8 | 后置 P5 |

## 2. 开发 TODO（P3 代码必落注释）

| 待办 | 落点 | 目标阶段 | 来源 |
|---|---|---|---|
| 运行中实时打断 / 修改中间结果 / 补充指令 / 终止 | `RuleBasedFlowEngine` + 工单模块 | P5+ | P3 §8 / ProjectBusiness 人机双向交互 |
| 多级审批链（资金类一级→二级） | `audit_ticket.audit_level` + 审批流 | P5+ | P3 §8 / ProjectBusiness 微调建议4 |
| 触发条件扩展（发票存疑 / 对公支付 / 跨部门分摊） | 触发规则配置化（P2 `finance_rule` 体系） | P4/P5 | P3 §8 |
| 幻觉拦截 → 正式评估体系 | 存疑标记 → P4 幻觉检测规则 + 监控大盘 | P4 | P3 §8 / 需求标准⑤③ |
| **工具幻觉：工具目录为空时 LLM 虚构工具编码** | `TaskPlanner`（规划层校验步骤工具编码）+ P3 `RuleBasedFlowEngine`（按业务绑定工具，不依赖 LLM 自由选） | P3 | P2a 实测：GENERIC 任务财务工具被 `filterTools` 过滤后目录为空，LLM 虚构 `expense_checker` 致任务 FAILED（T202608152311580979）；REIMBURSEMENT 有 `amount_verify` 不受影响 |

## 3. 代码遗留 TODO（P1 残留，跨阶段）

| 待办 | 落点 | 说明 | 目标阶段 |
|---|---|---|---|
| 分布式锁 | `common-redis-starter`（Redisson 3.47.0 版本已锁未实现） | 需求标准②并发冲突；P3 审批工单并发已提示 | P3（审批并发）/ P5 |
| 多模型 + Token 统计 + 故障切换 | `common-model-starter` `ChatClientFactory` | DeepSeek 已实现（P1.2），Qwen/Claude 对接、token 统计、备用模型切换缺 | P4（成本评估依赖） |

## 4. P4 阶段承接（可观测与评估）

| 待办 | 说明 | 来源 |
|---|---|---|
| 监控大盘 + ECharts 前端页 | 性能/QPS/耗时、成本/Token、成功率/任务完成率 可视化 | 需求标准⑤ + P1.5「本期不含」 |
| 量化评估报表 | 效率 / 风控 / 成本三类指标（审核准确率、拦截率、人工复核占比、Token 费用） | ProjectRequirements §七 / 需求标准⑤ |
| 定时任务（`task-job-service` / XXL-Job） | 预算刷新、过期上下文清理、评估报表生成 | 需求技术栈 / P0 计划 |
| 上下文分层治理 | Redis 临时会话、用户长期档案、Milvus 知识库 RAG、长会话摘要压缩、手动清空/重置 | 需求标准④ |
| 工具调用容错增强 | 超时、参数自动修正、熔断兜底 | 需求标准③2 |
| OCR 多厂商兜底 / 熔断降级 | `common-ocr-starter` `OcrService` 多实现 + 按配置顺序 failover + 连续失败熔断降级人工录入（现仅百度单厂商，D6 扩展位已留） | P2-execution-plan §3 D6 / §4 P2b |

## 5. P5 及长期（工程打磨）

| 待办 | 说明 | 来源 |
|---|---|---|
| 限流 / 熔断降级 | 网关 + 接口层，分布式限流 | 需求标准② |
| 操作日志分层 + 检索溯源 | 操作/业务/错误日志分层，按用户/租户/任务检索 | 需求标准②3 |
| FAILED 重跑 | 续跑当前仅 PENDING/RUNNING，FAILED 任务重跑留待 | P1.5-frontend-plan §6 |
| 工具治理增强 | 动态上下线、入参 Schema 强校验、高频工具结果缓存 | 需求标准③6 |
| 前端工程优化 | Element Plus 按需引入（1.2MB chunk 优化）、租户/RBAC 管理页、知识库管理页 | P1.5-frontend-plan 决策表 |
| 审批规则可视化配置高级版 | 审批触发规则 / 多级审批可视化 | 需求标准⑥1 |
| 安全加固深化 | 输出脱敏字段扩展、工具入参防注入深化、Prompt 注入规则库扩充 | 需求标准⑦ |
| 运维配套 | 数据库定时备份脚本、中间件启停脚本、README.en 补全 | 需求标准五/3 + P0 |

## 6. 已承接（无需动作）

| 项 | 说明 |
|---|---|
| 状态机 `MANUAL_REVIEW` 预留 | `finaudit-schema.sql` / `tables.md` 预留，P3 已扩展为 `APPROVAL_PENDING` / `REJECTED` |
| MinIO 本机启动 | P0 遗留，P2a 开工前启动即可（环境事项，非功能 TODO） |
