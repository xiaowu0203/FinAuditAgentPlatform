# 任务事件驱动编排（P1.3）

> agent-core-service 与 tool-service 之间通过 RabbitMQ 事件驱动协作，任务/步骤全程落库（持久化驱动），支持失败重试与断点续跑。

## 1. MQ 拓扑

交换机 `finaudit.task.exchange`（direct，持久化）。三个业务队列 + DLQ，均由 `common-mq-starter` 声明（幂等，两服务一致）。

| 队列 | routing key | 消费者 | 消息 |
|---|---|---|---|
| `finaudit.task.submit.q` | `task.submit` | agent-core | 任务已提交，触发启动 |
| `finaudit.tool.execute.q` | `tool.execute` | tool-service | 执行某工具步骤 |
| `finaudit.tool.result.q` | `tool.result` | agent-core | 工具执行结果，驱动推进 |
| `finaudit.dlq` | `dlq` | — | 失败/不可反序列化消息（死信） |

每个业务队列带死信参数：`x-dead-letter-exchange=finaudit.task.exchange`、`x-dead-letter-routing-key=dlq`，reject 消息自动进 DLQ。

## 2. 消息契约（JSON，共享 DTO 包 `com.finaudit.starter.mq.message`）

| 消息 | 字段 |
|---|---|
| `TaskSubmitMessage` | `taskId, tenantId` |
| `ToolExecuteMessage` | `taskId, stepId, tenantId, toolCode, inputParams` |
| `ToolResultMessage` | `taskId, stepId, tenantId, toolCode, result, success, errorMsg, costTimeMs` |

跨服务反序列化：`Jackson2JsonMessageConverter` + `DefaultJackson2JavaTypeMapper`，trusted packages 须**精确到消息 DTO 完整包名**（前缀匹配无效），见 `MqTopology.MESSAGE_PACKAGE`。

## 3. 状态机（落库驱动）

任务 `agent_task.status`：`PENDING → RUNNING → SUCCESS / FAILED`

步骤 `agent_task_step.status`：`PENDING → RUNNING → SUCCESS / FAILED`；TOOL 步骤失败且 `retryCount < 3` 时回 `RUNNING` 重发 `tool.execute`。

## 4. 事件流时序

```
用户 ──POST /api/v1/tasks──▶ agent-core：落库 PENDING → 发 task.submit
                                  │
  ┌─────── task.submit ──────────┘
  ▼
agent-core TaskSubmitConsumer → Orchestrator.start()
  任务 RUNNING → TaskPlanner.plan() 拆解步骤（LLM JSON / 内置模板回退）→ 逐条落库 PENDING
  → continueTask()：取首个非 SUCCESS 步骤
      ├─ LLM 步骤：内联调 DeepSeek → SUCCESS → 继续下一步
      └─ TOOL 步骤：发 tool.execute → RUNNING（不阻塞）
                                  │
  ┌─────── tool.execute ──────────┘
  ▼
tool-service ToolExecuteConsumer：注册表校验 → Redis 同入参缓存（命中直接返回）→ 执行 → 写 tool_execution_log → 发 tool.result
                                  │
  ┌─────── tool.result ───────────┘
  ▼
agent-core ToolResultConsumer → Orchestrator.onToolResult()
  成功 → 步骤 SUCCESS → continueTask() 推进
  失败 → retryCount<3 重发 tool.execute；≥3 → 步骤 FAILED → 任务 FAILED
  全部步骤 SUCCESS → finalizeSuccess() 汇总 result → 任务 SUCCESS
```

## 5. 失败重试

- 工具执行异常（业务校验失败 / 工具未启用 / 执行异常）→ `tool.result.success=false`
- agent-core 侧 `MAX_RETRY=3`：每次重试 `retryCount+1` 并重发 `tool.execute`；3 次后步骤/任务 FAILED
- 每队列消费者 `default-requeue-rejected=false`，异常消息进 DLQ，不无限重投
- 重试间隔：当前为即时重发（无退避），P2 可加延迟重试

## 6. 断点续跑

`POST /api/v1/tasks/{id}/resume`：

| 任务状态 | 行为 |
|---|---|
| `PENDING` | 完整启动（规划 + 执行）——覆盖 submit 消息丢失场景 |
| `RUNNING` | 残留 `RUNNING` 步骤重置为 `PENDING`，从首个非 SUCCESS 步骤续跑——覆盖服务重启场景 |
| `SUCCESS/FAILED` | 400 拒绝（任务已终结） |

## 7. Redis 缓存

`tool:exec:{toolCode}:{SHA-256(入参JSON)}`，TTL 1h。同入参工具执行命中直接返回（联调中任务 1 续跑复用任务 2 结果仅 7ms）。
