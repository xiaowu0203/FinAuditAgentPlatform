# common-mq-starter

事件驱动编排的 RabbitMQ 通用装配（交换机 / 队列 / DLQ / JSON 消息转换）。

## 拓扑

交换机 `finaudit.task.exchange`（direct，持久化），队列与 routing key 均由 `MqTopology` 常量声明：

| 队列 | routing key | 消费者 | 用途 |
|---|---|---|---|
| `finaudit.task.submit.q` | `task.submit` | agent-core | 任务已提交 → 启动流水线 |
| `finaudit.tool.execute.q` | `tool.execute` | tool-service | 执行某个 TOOL 步骤 |
| `finaudit.tool.result.q` | `tool.result` | agent-core | 工具结果回吐 → 推进下一步 |
| `finaudit.dlq` | `dlq` | — | 失败 / 不可反序列化消息（死信） |

三个业务队列都带死信参数（`x-dead-letter-exchange` + `x-dead-letter-routing-key=dlq`），reject 自动进 DLQ。
队列声明幂等，两个服务启动顺序无关。

## 消息契约

共享 DTO 包 `com.finaudit.starter.mq.message`（`TaskSubmitMessage` / `ToolExecuteMessage` / `ToolResultMessage`），
JSON 序列化；反序列化白名单**必须精确到该包名**（`MqTopology.MESSAGE_PACKAGE`），否则会因
`DefaultJackson2JavaTypeMapper` 的 trusted packages 校验失败把消息打进 DLQ。

## 使用

```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-mq-starter</artifactId>
</dependency>
```

```yaml
spring:
  rabbitmq:
    host: ${FINAUDIT_RABBIT_HOST:localhost}
    port: 5672
    username: ${FINAUDIT_RABBIT_USER:guest}
    password: ${FINAUDIT_RABBIT_PASSWORD:guest}
```

## 排错入口

- **步骤永久 RUNNING** → 先看 `finaudit.dlq`：不可反序列化（DTO 包名变更/字段类型不兼容）会直接进 DLQ，
  表现为「消息发了但没人推进」。
- **工具执行失败但任务没失败** → 确认失败路径是否仍回吐 `tool.result(success=false)`；
  agent-core 侧靠它推进/重试，不回吐就只能等任务级超时（R0-2 修复的就是这个）。
- 消费者入口必须用 `TenantContextHolder.runWith(tenantId, …)` 固定租户上下文，防止线程池复用串租户。

## 规划

- P3.8 R7-8：publisher confirm + DLQ 消费告警（日志级）+ 消费失败限次退避重试。
