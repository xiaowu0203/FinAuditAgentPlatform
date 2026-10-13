# 指标口径（P3.8 R9-3）

> 本文是 P4「量化评估」的**数据源口径文档**：只定义"指标怎么算、字段从哪来、SQL 怎么写"，
> 不做大盘、不做采集任务（P4 才建看板）。所有 SQL 均以 MySQL 5.7 语法为准，可直接在 `finaudit` 库执行。
>
> ⚠️ **口径必须与 `docs/ProjectRequirements.md` §七 对齐**：本文只把该节的需求指标落到具体表/列与 SQL 上，
> 不新增或改写业务定义。若两处冲突，以需求文档为准并回来修本文。

## 0. 先看数据源的三个前提
| 前提 | 说明 |
|---|---|
| **成本指标从 P3.8 R9-1 上线时刻起才有数据** | `model_call_log` 是新增表；此前的 Token 用量只在内存累加，进程重启即丢失，**无法回填**。做趋势对比时不要把上线前的区间算进来（否则会被误读成"用量暴涨"） |
| **耗时指标从 P3.8 R9-2 起才有值** | `agent_task.duration_ms` / `agent_task_step.duration_ms` 对历史行为 NULL；`AVG` 会自动忽略 NULL，但**样本数（COUNT）必须写成 `COUNT(duration_ms)`** 而不是 `COUNT(*)`，否则分母把历史行算进去会拉低均值 |
| **"人工等待时间"不计入效率指标** | `agent_task.duration_ms` 在进入 `APPROVAL_PENDING` 时即定格（`markApprovalPending`），财务审批耗时属另一类指标（见 §2.3），两者不可混算 |

## 1. 成本类（Token 用量）

数据源：`model_call_log`（一次模型调用一行；含 租户/任务/步骤/场景/模型/tokens/耗时/成功标记/是否切备用模型）。
另有一个**进程内即时观测出口**（不查库）：`GET /internal/metrics/model-usage`（agent-core 直连 9201，不经网关），
返回 `snapshot`（本进程启动至今累计）+ `window`（台账按时间窗聚合，可传 `from`/`to`）。
⚠️ **成本指标以 `window`/台账为准**：`snapshot` 随进程重启归零，只适合"刚重启后确认模型是否在工作"。

### 1.1 总量与失败率（按天）
```sql
SELECT DATE(created_at)                         AS day,
       COUNT(*)                                 AS calls,
       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed,
       ROUND(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) / COUNT(*) * 100, 2) AS fail_rate_pct,
       SUM(total_tokens)                        AS total_tokens,
       SUM(prompt_tokens)                       AS prompt_tokens,
       SUM(completion_tokens)                   AS completion_tokens,
       ROUND(AVG(latency_ms))                   AS avg_latency_ms,
       SUM(fallback_used)                       AS fallback_calls
FROM model_call_log
WHERE tenant_id = 1
GROUP BY DATE(created_at)
ORDER BY day DESC;
```

### 1.2 按场景拆成本（哪一环节最烧 Token）
`scene` 取值：`llm_step`（流水线 LLM 步骤）、`task_plan`（GENERIC 任务规划）。
```sql
SELECT scene,
       COUNT(*)              AS calls,
       SUM(total_tokens)     AS tokens,
       ROUND(AVG(total_tokens), 1) AS avg_tokens_per_call
FROM model_call_log
WHERE tenant_id = 1
GROUP BY scene
ORDER BY tokens DESC;
```

### 1.3 单任务的成本（成本归因到单据）
```sql
SELECT task_id,
       COUNT(*)          AS calls,
       SUM(total_tokens) AS tokens,
       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed
FROM model_call_log
WHERE tenant_id = 1 AND task_id IS NOT NULL
GROUP BY task_id
ORDER BY tokens DESC
LIMIT 20;
```

### 1.4 失败与重试
`model_call_log.success = 0` 只记最终失败（故障切换成功仍算成功，但会置 `fallback_used = 1`）。
结构化输出解析失败会自动重试一次，表现为**同一 step_id 出现多行**——这正是"一次步骤多行"的设计理由。
```sql
-- 调用次数 > 步骤数的步骤：说明发生了重试/切换（成本放大器）
SELECT step_id, COUNT(*) AS calls, SUM(total_tokens) AS tokens
FROM model_call_log
WHERE tenant_id = 1 AND step_id IS NOT NULL
GROUP BY step_id
HAVING calls > 1
ORDER BY calls DESC;

-- 备用模型切换（稳定性预警：主模型不稳会同时抬高成本与延迟）
SELECT DATE(created_at) AS day, COUNT(*) AS fallback_calls
FROM model_call_log
WHERE tenant_id = 1 AND fallback_used = 1
GROUP BY DATE(created_at);
```

## 2. 效率类

### 2.1 流水线耗时（任务级）
```sql
SELECT DATE(created_at)                                  AS day,
       COUNT(duration_ms)                                AS samples,       -- 只数有耗时的行
       ROUND(AVG(duration_ms) / 1000, 2)                 AS avg_sec,
       ROUND(MAX(duration_ms) / 1000, 2)                 AS max_sec,
       ROUND(SUM(CASE WHEN duration_ms > 60000 THEN 1 ELSE 0 END) / COUNT(duration_ms) * 100, 2) AS slow_over_60s_pct
FROM agent_task
WHERE tenant_id = 1 AND task_type = 'REIMBURSEMENT' AND status IN ('SUCCESS', 'APPROVAL_PENDING')
GROUP BY DATE(created_at)
ORDER BY day DESC;
```

### 2.2 哪一步最慢（步骤级）
```sql
SELECT s.step_name,
       COUNT(s.duration_ms)                   AS samples,
       ROUND(AVG(s.duration_ms) / 1000, 2)    AS avg_sec,
       ROUND(MAX(s.duration_ms) / 1000, 2)    AS max_sec
FROM agent_task_step s
JOIN agent_task t ON t.id = s.task_id
WHERE s.tenant_id = 1 AND s.deleted = 0 AND s.duration_ms IS NOT NULL
GROUP BY s.step_name
ORDER BY avg_sec DESC;
```
> 口径提示：TOOL 步骤的 `duration_ms` 含 MQ 往返与调度开销，工具自身耗时见
> `tool_execution_log.cost_time_ms`（两者之差即通信开销，用于判断瓶颈在工具还是消息链路）。

### 2.3 人工介入等待（审批时长，与流水线耗时分开看）
```sql
SELECT DATE(t.created_at)                              AS day,
       COUNT(*)                                        AS tickets,
       ROUND(AVG(TIMESTAMPDIFF(SECOND, t.created_at, r.first_action_at)) / 60, 1) AS avg_wait_minutes
FROM audit_ticket t
JOIN (SELECT ticket_id, MIN(created_at) AS first_action_at
      FROM audit_record
      WHERE action IN ('APPROVE', 'REJECT', 'TERMINATE')
      GROUP BY ticket_id) r ON r.ticket_id = t.id
WHERE t.tenant_id = 1 AND t.deleted = 0
GROUP BY DATE(t.created_at)
ORDER BY day DESC;
```

## 3. 风控类

### 3.1 自动通过率（★ 财务专属，也是 P3.8 决策 5 的阈值依据）
```sql
SELECT DATE(created_at) AS day,
       COUNT(*)         AS total,
       SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END)           AS auto_pass,
       SUM(CASE WHEN status = 'APPROVAL_PENDING' THEN 1 ELSE 0 END)  AS need_review,
       SUM(CASE WHEN status = 'REJECTED' THEN 1 ELSE 0 END)          AS rejected,
       ROUND(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) / COUNT(*) * 100, 2) AS auto_pass_pct
FROM agent_task
WHERE tenant_id = 1 AND task_type = 'REIMBURSEMENT'
  AND status IN ('SUCCESS', 'APPROVAL_PENDING', 'REJECTED')
GROUP BY DATE(created_at)
ORDER BY day DESC;
```
> ⚠️ 分母只含"已到达终态/待审"的任务：`RUNNING`/`CANCELLED` 不参与，否则自动通过率会被在途任务稀释。
> 口径与 `docs/planning/refactor-agent-autonomy.md` 的 R5-5 基线实测一致。

### 3.2 重复报销拦截率（★ 财务专属）
分子 = 因发票号硬命中重复而进入人工/被驳回的任务数；分母 = 有票据的报销任务数。
```sql
SELECT DATE(t.created_at) AS day,
       SUM(CASE WHEN t.status = 'APPROVAL_PENDING' THEN 1 ELSE 0 END)  AS reviewed,
       SUM(CASE WHEN t.trigger_type = 'RISK_HIT'
                 AND JSON_EXTRACT(t.review_findings, '$[*].code') LIKE '%DUPLICATE_INVOICE%'
                THEN 1 ELSE 0 END)                                      AS duplicate_hits
FROM audit_ticket t
WHERE t.tenant_id = 1 AND t.deleted = 0
GROUP BY DATE(t.created_at);
```
更精确的口径可直接查投影表（一票多单的权威事实）。
⚠️ 注意 `invoice_reimb_link` **只存 `invoice_record_id`／`reimb_id`，不含票号**，必须 join `invoice_record` 才能按票聚合：
```sql
-- 同一张票出现在多张未作废报销单 → 真实重复提交（含未被风控拦下的）
SELECT r.invoice_code, r.invoice_num, COUNT(DISTINCT l.reimb_id) AS reimb_count
FROM invoice_reimb_link l
JOIN invoice_record r ON r.id = l.invoice_record_id
WHERE l.tenant_id = 1 AND l.deleted = 0 AND r.deleted = 0
GROUP BY r.invoice_code, r.invoice_num
HAVING reimb_count > 1
ORDER BY reimb_count DESC;
```

### 3.3 预算超支预警次数（★ 财务专属）
```sql
-- 口径 A：流水线判定预算不足而转人工（findings 带 BUDGET_INSUFFICIENT）
SELECT DATE(created_at) AS day, COUNT(*) AS budget_insufficient_tickets
FROM audit_ticket
WHERE tenant_id = 1 AND deleted = 0
  AND JSON_EXTRACT(review_findings, '$[*].code') LIKE '%BUDGET_INSUFFICIENT%'
GROUP BY DATE(created_at);

-- 口径 B：部门月度预算用到 80% 以上的部门×月份（事前预警，非事后拦截）
SELECT dept_name, period, total_budget, used_amount,
       ROUND(used_amount / total_budget * 100, 2) AS used_pct
FROM budget
WHERE tenant_id = 1 AND deleted = 0 AND total_budget > 0
HAVING used_pct >= 80
ORDER BY used_pct DESC;
```

### 3.4 票据识别准确率（★ 财务专属）
`expense_attachment.ocr_status` 取值语义见 `docs/api/file-service.md`；本指标 = 识别成功张数 / 上传附件张数。
```sql
SELECT DATE(created_at) AS day,
       COUNT(*)                                                  AS attachments,
       SUM(CASE WHEN ocr_status = 'SUCCESS' THEN 1 ELSE 0 END)   AS ocr_success,
       ROUND(SUM(CASE WHEN ocr_status = 'SUCCESS' THEN 1 ELSE 0 END) / COUNT(*) * 100, 2) AS ocr_success_pct
FROM expense_attachment
WHERE tenant_id = 1 AND deleted = 0
GROUP BY DATE(created_at)
ORDER BY day DESC;
```
> ⚠️ **这是"识别成功率"，不等于"识别准确率"**：准确率需要人工标注的对照集（字段级 抽取值 vs 人工值），
> 当前库内**没有**该标注数据。要真正度量准确率，须先按 `docs/test/README.md` 的评估用例模板建立标注样本，
> 再按字段比对。**本项尚未采集，勿把成功率当作准确率对外汇报。**

## 4. 尚未采集的指标（诚实清单）

| 指标 | 现状 | 补齐所需 |
|---|---|---|
| 票据识别**准确率**（字段级） | ❌ 仅有成功率 | 人工标注对照集 + 比对脚本（评估模板已备） |
| 业务**通过率**（财务终审通过占比） | ⚠️ 可算，但分子需按 `audit_ticket.status='APPROVED'` 与自动通过区分 | 口径确认后补 SQL（本文只给自动通过率） |
| **单均成本**（元/单） | ❌ 缺模型单价 | `model_call_log` 已有 tokens；需配置每千 token 单价后换算 |
| 用户维度效率（提交→审批完成） | ⚠️ 部分可算 | 需要报销单提交时间与审批终态的端到端耗时定义 |
| 工具调用成功率 | ✅ 可算（`tool_execution_log.status`） | 口径明确后补 SQL（P4 一并做） |

## 5. 与需求文档的对照

| `ProjectRequirements.md` §七 的类别 | 本文对应 | 数据源 |
|---|---|---|
| 效率 | §2 | `agent_task.duration_ms`、`agent_task_step.duration_ms`、`audit_record` |
| 风控 | §3 | `audit_ticket.review_findings`、`invoice_reimb_link`、`expense_attachment.ocr_status` |
| 成本 | §1 | `model_call_log` |
| 财务专属（票据识别准确率 / 重复报销拦截率 / 预算超支预警次数 / 自动通过率） | §3.1~§3.4 | 同上 |

> 维护约定：**新增采集列或改变写入口径时，本文与对应阶段的迁移脚本、计划文档必须同 commit 更新**
> （与 R7-9 的「文档零形状级错误」同一纪律）。
