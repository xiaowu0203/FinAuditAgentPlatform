# 主动通知（站内信 / Webhook）— P3.8 R8-2

> 面向开发与集成方：**站内信**给"人"看（谁的单子被怎么处理了），**Webhook**给"系统"用（推送事件给 OA / 数据仓库 / 告警平台）。
> 在此之前全仓无任何通知能力，进度完全依赖前端轮询（走查记录 B-8）。

## 1. 总览

```
业务事件（转人工 / 驳回 / 撤销请求 / 自动通过 / 失败 / MQ 死信）
      │
      ▼
NotifyFacade            ← 业务语义 → 事件码 + 收件人 + 文案（唯一映射点）
      │
      ▼
NotifyEventPublisher    ← 与业务同事务，任一通道失败都不上抛
      ├──► 站内信（notify_message）          同步写库，立刻可见
      └──► Webhook（notify_delivery, outbox） 定时任务投递，HMAC 签名，失败退避重试
```

两条硬性纪律：

1. **通知失败绝不阻断业务**。工单该驳回归驳回、任务该收尾归收尾；写一条提醒失败不会回滚审批事务。
2. **与业务同事务**（outbox 语义）：业务提交成功 ⇒ 站内信与投递记录必在；业务回滚则通知一起回滚，
   不会出现"通知说已通过、其实事务回滚了"的假消息。

## 2. 站内信

### 2.1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/notify/messages?pageNum&pageSize&unreadOnly` | 我的消息分页（按创建时间倒序） |
| GET | `/api/v1/notify/messages/unread-count` | 未读数（`{ "unread": 3 }`），前端红点用 |
| POST | `/api/v1/notify/messages/{id}/read` | 标记单条已读（只能标本人的；已读再标不报错） |
| POST | `/api/v1/notify/messages/read-all` | 全部标记已读（返回更新条数） |

**权限**：这四个端点**不挂权限码**——"能看自己的提醒"是登录用户的基本能力。
越权防线在数据层：收件人 ID 一律取登录上下文（`X-User-Id`），所有查询/更新的 WHERE 里都带 `user_id`，
故拿到别人的消息 id 也读不到、改不动。

出参字段（`NotifyMessageVO`）：`id / category / eventType / title / content / bizType / bizId / link / read / readAt / createdAt`。

- `category`：`AUDIT`（审核流转，申请人/审批人视角）或 `ALERT`（平台告警，运维视角）——前端按此分组。
- `link`：可直接跳转的前端路径，如 `/reimbursements/7`（申请人）、`/audits/12`（审批人）。
- `read`：布尔量，前端不必自己判 `readAt === null`。

### 2.2 收件人怎么定

| 收件人 | 解析方式 | 说明 |
|---|---|---|
| 申请人 | 任务/工单的 `created_by` | 本地字段，无需跨服务 |
| 审批人 | 按权限码 `audit:approve` 反查 | 经内部端点 `GET /internal/users/by-perm`（tenant-service） |
| 管理员 | 按权限码 `notify:manage` 反查 | 平台告警（MQ 死信、Webhook 放弃）的收件人 |

**为什么要反查而不能从上下文取**：这些通知由**后台线程**触发（MQ 消费、任务收尾、定时投递），
那里没有登录用户上下文。"有新工单待审批"恰恰是最该主动提醒的场景，只能按权限码反查。

**反查失败（tenant-service 不可达）时降级为"不通知该组"**并打 WARN——少发一条提醒可以接受，
因为查不到审批人就把工单审批拖死不可接受。

### 2.3 幂等

`notify_message` 上有唯一索引 `uk_notify_dedupe (tenant_id, dedupe_key)`，`dedupe_key` 为 `NULL` 表示不去重
（MySQL 唯一索引视多个 NULL 为互不冲突）。

**业务事件刻意不设幂等键**："驳回 → 修改重跑 → 再驳回"是三次真实事件，用 `(事件, 单据)` 去重会把第二次提醒静默吃掉。
只有"同一物理事件可能被重复投递"的场景才设键：MQ 死信告警（`MQ_DLQ_ALERT:<routingKey>:<body摘要>`）、
Webhook 放弃告警（`WEBHOOK_DEAD:<deliveryId>:<userId>`）。群发时幂等键会自动拼接收件人后缀，
否则唯一索引会把第二个收件人顶掉。

## 3. Webhook

### 3.1 配置端点（挂 `notify:manage`，默认仅 admin 角色持有）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/notify/webhooks` | 配置列表（密钥只回显掩码 `ab****gh`） |
| GET | `/api/v1/notify/webhooks/event-types` | 事件码目录（前端订阅多选框数据源） |
| POST | `/api/v1/notify/webhooks` | 新建（地址做 SSRF 校验；重名拒绝） |
| PUT | `/api/v1/notify/webhooks/{id}` | 更新（部分更新：空字段不改动；`secret` 留空 = 保留原密钥） |
| DELETE | `/api/v1/notify/webhooks/{id}` | 逻辑删除（投递台账保留，便于事后核对） |
| POST | `/api/v1/notify/webhooks/{id}/test` | 测试投递（登记一条 `WEBHOOK_TEST`，走完整 outbox 链路），返回投递记录 ID |
| GET | `/api/v1/notify/webhooks/deliveries?webhookId&status&pageNum&pageSize` | 投递台账（排障入口） |
| POST | `/api/v1/notify/webhooks/deliveries/{deliveryId}/retry` | 人工重投（清零次数、立刻到期） |
| GET | `/api/v1/notify/webhooks/pending-count` | 待投递积压数（持续增长 = 对端不可用） |

配置字段：`name / url / secret / eventTypes / enabled / maxAttempts(默认3,上限10) / timeoutMs(默认5000,上限60000)`。
`eventTypes` 为空数组表示**订阅全部**（新建时不该强迫管理员先勾一遍）。

### 3.2 投递请求（接收方按此实现校验）

```http
POST /your-hook HTTP/1.1
Content-Type: application/json; charset=utf-8
User-Agent: FinAuditAgentPlatform-Webhook/1.0
X-Finaudit-Event: TICKET_REJECTED
X-Finaudit-Delivery: 3f2a9c1d8e7b4a5f9c0d1e2f3a4b5c6d
X-Finaudit-Timestamp: 1767225600
X-Finaudit-Signature: sha256=<hex>

{"eventId":"3f2a...","eventType":"TICKET_REJECTED","occurredAt":"2026-01-01T12:00:00Z",
 "tenantId":1,"bizType":"TICKET","bizId":12,"title":"报销单被驳回","content":"...",
 "link":"/audits/12","data":{"ticketNo":"AT-T1","action":"REJECT","comment":"金额超标"}}
```

**签名算法**：`signature = HMAC-SHA256(secret, timestamp + "." + rawBody)`，十六进制小写，头值加 `sha256=` 前缀。

接收方校验三步：
1. 用**原始 body 字节**（不要先反序列化再重新序列化——字段顺序/空白不同会导致签名不符）与
   `timestamp`、`secret` 复算 HMAC，**常量时间比较**；
2. 检查 `timestamp` 与当前时间差在可接受窗口内（建议 ±5 分钟）——时间戳参与签名，故抓到旧请求改时间戳必然验签失败；
3. 用 `X-Finaudit-Delivery` 做**幂等去重**（重试会原样重发同一 `eventId`）。

Java 校验示例（与本仓 `WebhookSigner` 同口径）：

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
String base = timestampHeader + "." + rawBody;
String expected = HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
boolean ok = MessageDigest.isEqual(expected.getBytes(UTF_8), providedHex.getBytes(UTF_8));
```

### 3.3 投递语义（outbox）

| 环节 | 行为 |
|---|---|
| 登记 | 业务事务内写 `notify_delivery`（`status=PENDING`，`next_retry_at=now`） |
| 取件 | 定时任务（默认 15s 一轮）先枚举"有到期记录的租户"，再逐租户取件（多租户上下文隔离） |
| 认领 | `attempt_count` 当版本号做**乐观认领**：0 行受影响 = 别的实例/轮次已取走，直接跳过（多实例安全） |
| 投递 | JDK `HttpClient` POST，超时取配置值；**每次投递前重新校验地址**（防 DNS 变更） |
| 成功 | HTTP 2xx → `SUCCESS`（记录 `delivered_at`、`last_http_status`） |
| 失败 | 未达次数 → 留 `PENDING` 并按下一次数取退避（默认 1s → 10s → 60s）；达次数 → `DEAD` |
| 放弃 | `DEAD` 时给**配置创建人**发站内信告警；⚠️ **不再触发 Webhook 事件**（否则"Webhook 挂了 → 告警走同一个挂掉的通道"自我放大） |
| 人工重投 | `resetForRetry()` 清零次数并立刻到期（管理端点触发） |

**为什么用 DB outbox 而不是 MQ**：投递行与业务数据同事务提交，"业务成功 ⇒ 必投递"是免费的；
重启不丢、可用 SQL 排障、人工重投就是改一行；也回避了"先发 MQ 后提交业务、消费者抢在提交前处理"的经典竞态。
代价是多一次表写入 + 一个定时任务。

## 4. 事件目录

| 事件码 | 触发点 | 站内信收件人 | 建议订阅方 |
|---|---|---|---|
| `TASK_NEED_REVIEW` | 流水线判定需人工复核（含预算不足转人工） | 申请人 | OA/通知机器人 |
| `TASK_AUTO_PASSED` | AUTO_PASS 自动通过（含 GENERIC 任务成功） | 申请人 | 财务看板 |
| `TASK_FAILED` | 流水线失败（步骤重试耗尽 / 任务超时） | 申请人 | 运维告警 |
| `TICKET_CREATED` | 新建审批工单待处理 / 修改重跑后再次待审批 | 有 `audit:approve` 者 | 财务待办系统 |
| `TICKET_APPROVED` | 财务审批通过 | 申请人 | 报销系统 |
| `TICKET_REJECTED` | 财务审批驳回（含审批意见） | 申请人 | 报销系统 |
| `TICKET_TERMINATED` | 终止 / 撤销已同意（单据作废） | 申请人 | 报销系统 |
| `TICKET_WITHDRAW_REQUESTED` | 申请人发起撤销申请 | 有 `audit:approve` 者 | 财务待办系统 |
| `TICKET_WITHDRAW_REFUSED` | 撤销申请被拒绝（单据保持有效） | 申请人 | — |
| `MQ_DLQ_ALERT` | MQ 死信（R7-8 收口：DLQ 从"只打日志"变成可投递告警） | 有 `notify:manage` 者 | 告警平台 |
| `WEBHOOK_DEAD` | Webhook 投递次数用尽 | 配置创建人 | 仅站内信（不生成投递） |
| `WEBHOOK_TEST` | 测试投递端点 | 无（直接指定目标配置） | — |

动作 `SUBMIT / AMEND / RERUN / RERUN_FAILED / WITHDRAW / WITHDRAW_REQ` 属**过程性留痕**，只落审计记录不发通知——
给每个内部动作都发提醒只会让真正的结局被噪音淹没。

## 5. MQ 死信告警接入（R7-8 遗留项收口）

`common-mq-starter` 新增 SPI `MqAlertSink`（与 R9-1 的 `ModelCallRecorder` 同一套模式）：
starter 只定义接口、不依赖数据源；agent-core 实现 `MqAlertNotifySink`（管理员站内信 + `MQ_DLQ_ALERT` 事件）。

⚠️ **竞争消费坑**：agent-core 与 tool-service 都引入 `common-mq-starter`，若不处理，两者会同时监听
`finaudit.dlq` 形成**竞争消费**——告警随机落在一边，而只有 agent-core 持有告警通道，
落在 tool-service 的那一半就只剩不落盘的日志。故 tool-service 显式配置
`finaudit.mq.dlq-alert.enabled: false`，由 agent-core 独占消费。

## 6. 配置项（agent-core）

| 键 | 默认 | 说明 |
|---|---|---|
| `finaudit.notify.allow-private-address` | `false` | 是否允许投递到内网/环回地址（SSRF 开关）。**仅本地联调置 true**，启动会打 WARN |
| `finaudit.notify.delivery-enabled` | `true` | 定时投递开关（自测可关，改由脚本手工触发） |
| `finaudit.notify.delivery-interval-ms` | `15000` | 投递轮次间隔（固定延迟） |
| `finaudit.notify.delivery-batch-size` | `50` | 单轮最多取件数 |
| `finaudit.notify.retry-backoff-seconds` | `[1,10,60]` | 失败退避序列（按尝试次数取，末项重复使用） |
| `finaudit.notify.connect-timeout-ms` | `3000` | 建连超时（单请求超时取 Webhook 配置的 `timeoutMs`） |

环境变量形式：`FINAUDIT_NOTIFY_ALLOW_PRIVATE_ADDRESS` / `FINAUDIT_NOTIFY_DELIVERY_ENABLED`（见 `.env.example`）。

## 7. 已知取舍与残余风险（诚实清单）

| 项 | 现状 | 建议 |
|---|---|---|
| `secret` 明文入库 | HMAC 密钥需可还原才能签名，故明文存储（对外只回显掩码） | 生产接 KMS 或密文列 |
| SSRF 的 TOCTOU | 配置时与每次投递前都校验，但 DNS 解析与 HTTP 连接之间仍有窗口（DNS rebinding） | 彻底消除需把校验后的 IP 钉死用于连接 |
| 单实例调度 | 认领是乐观锁（多实例安全），但没有任何实例时投递不推进 | 生产接 job 调度中心或独立 worker |
| 日志非持久化 | 投递失败原因落 `last_error`（500 字符截断） | 完整报文需查对端或 broker |
| 无重放窗口强制 | 服务端发送 `X-Finaudit-Timestamp`，**是否校验时间窗由接收方决定** | 接收方实现建议 ±5 分钟窗口 |
| 站内信失败只落日志 | 失败原因往往就是"写不进库"，无处可写（AGENTS.md §5.14 的边界情形） | 已把可预见失败前移：实体边界截断 + 唯一索引显式去重 |

## 8. 端到端验证

`docs/test/r8-notify-e2e.ps1`（本地 HttpListener 充当接收方）：

```powershell
powershell -ExecutionPolicy Bypass -File docs\test\r8-notify-e2e.ps1 `
  -MySqlExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysql.exe" -User root -Password root
```

判据：① 站内信真的写给对的人（提交一张单 → 命中转人工 → 申请人 + 审批人各收到一条）；
② 未读数与已读标记正确；③ Webhook 收到请求且**签名经服务端算法复算通过**；
④ 对端返回 500 时按退避重试、次数用尽置 `DEAD` 并给创建人写告警站内信。
