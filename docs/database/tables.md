# FinAudit 数据库设计（P2a-重构）

> 目标库：`finaudit`（MySQL 5.7 / utf8mb4 / InnoDB）。完整建库脚本见 [`finaudit-schema.sql`](./finaudit-schema.sql)。

## 0. 通用约定

| 约定 | 说明 |
|---|---|
| 主键 | `id BIGINT AUTO_INCREMENT` |
| 多租户 | 业务表均含 `tenant_id BIGINT`（默认 1），配合 `TenantLineInnerInterceptor` 自动过滤 |
| 时间 | `created_at` / `updated_at DATETIME`，`ON UPDATE CURRENT_TIMESTAMP` |
| 逻辑删除 | 各表 `deleted TINYINT DEFAULT 0`（0 未删 / 1 已删）；例外：`finance_rule` 因 `uk_rule_type` 唯一索引改用 **`deleted BIGINT`（0 未删 / 主键id 已删，见 §13）** |
| 金额 | 一律 `DECIMAL` + 代码层 `BigDecimal`，严禁 float/double（通用 `DECIMAL(18,2)`；报销单表按 P2 规格用 `DECIMAL(12,2)`） |
| JSON 列 | 入参/结果/Schema 用 `JSON` 类型，MyBatis-Plus 以 `JacksonTypeHandler` 映射；**仅作存储，不参与 WHERE 过滤**（MySQL 5.7 限制） |
| 字符集 | `utf8mb4`（兼容中文 + emoji） |

## 1. sys_tenant 租户表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_code | VARCHAR(32) UK | 租户编码 |
| tenant_name | VARCHAR(64) | 租户名称 |
| status | TINYINT | 1 启用 / 0 禁用 |
| created_at / updated_at | DATETIME | |
| deleted | TINYINT | 逻辑删除 |

## 2. sys_user 用户表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID，UK(tenant_id, username) |
| username | VARCHAR(64) | 登录名 |
| password | VARCHAR(128) | **BCrypt 哈希**（禁止明文） |
| real_name | VARCHAR(64) | 真实姓名 |
| phone | VARCHAR(20) | 手机号 |
| dept_id | BIGINT | 部门 ID（P3.5b 员工级归属；未绑定 null，FK→sys_dept.id 语义非物理外键） |
| status | TINYINT | 1 启用 / 0 禁用 |
| created_at / updated_at / deleted | | |

## 2.5 sys_dept 部门表（P3.5b）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | UK(tenant_id, dept_name)，租户自动隔离 |
| parent_id | BIGINT | 父部门 ID（0=根），内存组树（MySQL 5.7 无递归 CTE） |
| dept_name | VARCHAR(64) | 部门名称（权威主数据；业务表仅存 dept_id + 提交时快照） |
| status | TINYINT | 1 启用 / 0 停用 |
| created_at / updated_at / deleted | | |

写约束：create 父存在性；update 防环（不能挂到自身子孙下）；delete 有子部门/用户引用拒删。

## 3. sys_role 角色表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | UK(tenant_id, role_code) |
| role_code | VARCHAR(32) | 角色编码（admin / auditor） |
| role_name | VARCHAR(64) | 角色名称 |

## 4. sys_user_role 用户角色关联表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id / user_id / role_id | BIGINT | UK(tenant_id, user_id, role_id) |
| created_at | DATETIME | |

## 5. agent_task Agent 任务表（任务持久化 + 状态机载体）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| task_no | VARCHAR(40) UK | 任务编号，如 `T20260813120000123456` |
| title | VARCHAR(128) | 任务标题 |
| task_type | VARCHAR(20) | 业务类型：`REIMBURSEMENT` 报销审核 / `GENERIC` 通用分析（P2a 新增，规划器按业务注入提示词/工具） |
| input_params | JSON | 任务入参（原始输入，含明细金额等） |
| status | VARCHAR(20) | 任务状态，见下方状态机 |
| started_at | DATETIME | 本次执行开始时间（启动/修改重跑刷新；任务级超时预算计时起点，P3.5d） |
| total_steps | INT | 总步骤数 |
| finished_steps | INT | 已完成步骤数 |
| result | JSON | 最终结果（P3a 汇总 JSON；REIMBURSEMENT 可含 `steps`、`flowBranch`（`AUTO_PASS`/`NEED_REVIEW`）及 `reviewReasons`） |
| error_msg | VARCHAR(1024) | 失败原因 |
| created_by | BIGINT | 提交人用户 ID |
| created_at / updated_at / deleted | | 索引 `idx_tenant_status(tenant_id, status)`、`idx_created_at` |

**任务状态机**：`PENDING → RUNNING → SUCCESS / FAILED / APPROVAL_PENDING / REJECTED`。P3b 起 `APPROVAL_PENDING` 已生成审批工单（见 §14）；`REJECTED` 由审批驳回/终止产生（区别于系统 `FAILED`）。

## 6. agent_task_step Agent 任务步骤表（断点续跑载体）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | |
| task_id | BIGINT | 任务 ID，UK(task_id, step_no, deleted)（含 deleted：重规划软删历史步骤不占唯一名额） |
| step_no | INT | 步骤序号（从 1 开始） |
| step_name | VARCHAR(64) | 步骤名称 |
| step_type | VARCHAR(16) | `LLM`（模型推理）/ `TOOL`（工具调用） |
| tool_name | VARCHAR(64) | TOOL 步骤的工具编码 |
| agent_role | VARCHAR(32) | P3a 执行角色：SCHEDULER / DOCUMENT_PARSER / BUDGET_CALCULATOR / RULE_VALIDATOR / RISK_AUDITOR；历史与 GENERIC 步骤可空 |
| input_params | JSON | 步骤入参 |
| output | JSON | 步骤输出 |
| status | VARCHAR(20) | 步骤状态 |
| error_msg | VARCHAR(1024) | 失败原因 |
| retry_count | INT | 重试次数 |
| created_at / updated_at / deleted | | `deleted` 为 **BIGINT**（0 未删 / 主键id 已删，配合 uk_task_step）；索引 `idx_task(task_id)`、`idx_status(status)` |

**步骤状态机**：`PENDING → RUNNING → SUCCESS / FAILED`（同任务状态机；FAILED 且 `retry_count < 3` 时允许重试）。

## 7. tool_registry 工具注册表（工具治理）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | UK(tenant_id, tool_code) |
| tool_code | VARCHAR(64) | 工具编码，如 `amount_verify` |
| tool_name | VARCHAR(64) | 工具名称 |
| description | VARCHAR(256) | 工具描述 |
| input_schema | JSON | **入参 JSON Schema**（强校验） |
| enabled | TINYINT | 1 启用 / 0 禁用 |
| version | VARCHAR(16) | 工具版本，默认 1.0 |
| created_at / updated_at / deleted | | |

## 8. tool_execution_log 工具执行日志表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | |
| task_id / step_id | BIGINT | 关联任务/步骤 |
| tool_code | VARCHAR(64) | 工具编码 |
| input_params | JSON | 入参 |
| result | JSON | 执行结果 |
| cost_time_ms | BIGINT | 耗时（毫秒） |
| status | VARCHAR(20) | `SUCCESS` / `FAILED` |
| created_at | DATETIME | 索引 `idx_task(task_id)`、`idx_tool(tool_code)` |

## 9. expense_reimbursement 报销单表（P2a 单据闭环）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| reimb_no | VARCHAR(40) UK | 报销单号，如 `R2026081512345678` |
| title | VARCHAR(128) | 报销标题 |
| expense_type | VARCHAR(32) | 费用类型：`TRAVEL` / `ENTERTAINMENT` / `OFFICE` |
| applicant_id | BIGINT | 申请人用户 ID（来源 `X-User-Id`） |
| dept_name | VARCHAR(64) | 部门（**提交时快照**，P3.5b 起权威为 dept_id；resubmit 部门不可改） |
| dept_id | BIGINT | 提交者部门 ID（P3.5b 权威关联键；旧单/未选部门为 null） |
| total_amount | DECIMAL(12,2) | 申报总金额（服务端按明细求和，不信任客户端） |
| task_id | BIGINT | 关联 `agent_task.id`（agent-core 服务内同事务创建任务并回填） |
| status | VARCHAR(20) | 审核状态，对齐任务状态机 |
| claim_date | DATE | 报销日期 |
| remark | VARCHAR(512) | 备注 |
| items | JSON | 报销明细 `[{name,amount,amountType,quantity,unitPrice,date,city,hotelDays,hotelAmount,transportAmount,subsidyAmount}]`（P2c 差旅/补贴评估字段均 JSON 内嵌） |
| created_at / updated_at / deleted | | 索引 `uk_reimb_no`、`idx_tenant_status(tenant_id,status)`、`idx_applicant(tenant_id,applicant_id)`、`idx_task(task_id)` |

**状态机**：`PENDING → RUNNING → SUCCESS / FAILED / APPROVAL_PENDING / REJECTED`。任务进入 `APPROVAL_PENDING` 时，报销单同步为 `MANUAL_REVIEW`；P3b 起审批动作会经审批工单（见 §14/§15）驱动，approve → `SUCCESS`、reject/terminate → `FAILED`（任务侧 `REJECTED`）。

## 10. expense_attachment 报销业务附件表（P2a-重构）

> P2a-重构后仅存 `file_record` 引用 + 业务字段（fileType/ocrStatus/ocrResult）；文件元数据（fileName/objectName）在 file-service 的 `file_record`，业务侧经 `FileServiceFeign` 联取。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| reimb_id | BIGINT | 报销单 ID（提交报销单绑定后回填，未关联前为 NULL） |
| file_record_id | BIGINT | **`file_record.id`（文件元数据在 file-service），索引 `idx_file_record`** |
| file_type | VARCHAR(32) | 附件类型：`INVOICE` / `ITINERARY` / `CONTRACT` / `OTHER`（P2a 默认 `OTHER`，分类归 P2b OCR 产生） |
| ocr_status | VARCHAR(16) | OCR 状态：`PENDING` / `SUCCESS` / `FAILED`（P2b 使用） |
| ocr_result | JSON | OCR 抽取结果（P2b 使用） |
| created_at / updated_at / deleted | | 索引 `idx_reimb(reimb_id)`、`idx_file_record(file_record_id)`、`idx_tenant(tenant_id)`、`idx_ocr_status(ocr_status)` |

## 11. file_record 文件元数据表（file-service：纯二进制资源）

> 归属 file-service，**无任何财务业务字段**；上传（multipart → 对象存储）唯一产生本表数据。业务附件经 `expense_attachment.file_record_id` 引用本表，读文件一律经 `FileServiceFeign` 远程调用。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| created_by | BIGINT | 上传人 ID（P3.5c 直接预览/下载归属校验：本人或 reimb/audit:viewAll） |
| file_name | VARCHAR(255) | 原始文件名 |
| object_name | VARCHAR(255) | **对象存储 key（含租户前缀 `{tenantId}/{yyyyMM}/{uuid}{ext}`，防跨租户碰撞）** |
| content_type | VARCHAR(128) | MIME 类型，默认 `application/octet-stream` |
| size | BIGINT | 字节大小 |
| created_at / updated_at / deleted | | 索引 `idx_tenant(tenant_id)` |

## 12. budget 部门预算表（P2b）

> 归属 agent-core；`total_budget`/`used_amount` 一律 DECIMAL（金额 Decimal 强制）。审核通过后 `used_amount` 累加。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| dept_name | VARCHAR(64) | 部门（冗余显示，P3.5b 起权威为 dept_id） |
| dept_id | BIGINT | 部门 ID（P3.5b 权威关联键，NOT NULL，FK→sys_dept.id 语义） |
| period | VARCHAR(7) | 预算周期 `YYYY-MM` |
| total_budget | DECIMAL(14,2) | 预算总额 |
| used_amount | DECIMAL(14,2) | 已用额度（审核通过后累加） |
| created_at / updated_at / deleted | | 唯一键 `uk_dept_period(tenant_id, dept_id, period)`（P3.5b 切换） |

## 13. finance_rule 财务规则表（P2b 建表 / P2c 可视化配置 + Nacos 动态刷新）

> 归属 agent-core；`rule_config` 为结构化 JSON（**仅存储不参与 WHERE**，MySQL 5.7 限制）。P2c 起：
> - `published=1` 为**生效集**（发布写 Nacos `finaudit-rules-{tenantId}`，应用端监听即时生效，改规则不重启）；`save/update/toggle` 置 `published=0`（草稿，需重新发布）。
> - 评估数据源：优先 Nacos 已发布快照（`TenantNacosConfigHelper` 监听 + 缓存 TTL），无配置降级 DB 直查。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| rule_code | VARCHAR(64) | 规则编码（创建后不可改，UK `uk_rule(tenant_id, rule_code)`） |
| rule_name | VARCHAR(64) | 规则名称 |
| rule_type | VARCHAR(32) | `AMOUNT_LIMIT` / `REIMBURSE_EXPIRE` / `TRAVEL_STANDARD` / `SUBSIDY_LIMIT`；**同租户同类型唯一**（UK `uk_rule_type(tenant_id, rule_type, deleted)`，业务层 + SQL 双层兜底） |
| rule_config | JSON | 结构化规则：`{"threshold":5000}` / `{"maxDays":30}` / `{"standards":[{city,hotelDaily,transportTotal}]}` / `{"dailyAmount":200}` |
| enabled | TINYINT | 启停：1 启用 / 0 禁用 |
| published | TINYINT | 是否已发布 Nacos：1 生效 / 0 草稿 |
| version | VARCHAR(16) | 规则版本（发布自增） |
| created_at / updated_at / deleted | | 逻辑删除，规则不物理删除；**deleted=BIGINT**（0 未删 / 主键id 已删）——删除实现必须自定义 `SET deleted=id`，禁用 MP 默认写 1，否则与 `uk_rule_type` 冲突 |

## 14. audit_ticket 审批工单表（P3b 人机协同审批闭环）

> 归属 agent-core。触发：任务流水线判定 `NEED_REVIEW` 时生成（`review_reasons` 复核原因 + `trigger_type` 确定性映射：`OVER_LIMIT` 大额/超标 / `RULE_FAIL` 规则不通过 / `RISK_HIT` 风控命中，`LLM_DECISION` 兜底归 `RISK_HIT`）。工单与任务 **1:1**（`uk_task(tenant_id, task_id)` 唯一键），同单续跑复用同一工单。

**状态机（P3b 工作流重设计）**：

```
PENDING → APPROVED / REJECTED / TERMINATED / WITHDRAWN / AMENDED
  ↘ AMENDED(rerunning) →(重跑再次命中复核) PENDING   （reviewReasons/trigger 刷新 + RERUN 留痕）
                        →(重跑 AUTO_PASS)  APPROVED  （系统留痕 comment=改金额重跑后自动通过）
                        →(重跑 FAILED)     PENDING   （onRerunFail 复位 + RERUN_FAILED 留痕）
APPROVED → WITHDRAW_PENDING →(财务同意) WITHDRAWN
                             →(财务拒绝) APPROVED（原地返回）
PENDING → WITHDRAWN（提交人撤回，直接生效）
```

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| task_id | BIGINT | 关联 `agent_task.id`，UNIQUE `uk_task(tenant_id, task_id)`（P3b：同单续跑 1:1，防同任务重复建单竞态） |
| ticket_no | VARCHAR(64) | 工单编号 `AT-{taskNo}`，UK `uk_ticket_no(tenant_id, ticket_no)` |
| title | VARCHAR(128) | 任务标题（冗余展示） |
| trigger_type | VARCHAR(32) | 触发类型（见上；重跑再次命中时刷新） |
| risk_desc | VARCHAR(512) | 复核原因描述（review_reasons join 截断；重跑再次命中时刷新） |
| step_no | INT | 触发步骤（预留：决策跨步骤，暂置 NULL） |
| origin_amount | DECIMAL(12,2) | 申报总额（任务入参 `claimedTotal`） |
| adjusted_amount | DECIMAL(12,2) | 提交人修改重跑后总额（resubmit 时写，重跑后为最终金额） |
| status | VARCHAR(20) | `PENDING` / `APPROVED` / `REJECTED` / `AMENDED` / `TERMINATED` / `WITHDRAW_PENDING` / `WITHDRAWN`，索引 `idx_tenant_status(tenant_id,status)` |
| audit_level | TINYINT | 审批级数，P3 恒 1（预留多级审批 TODO P5+） |
| rerun_count | INT | 提交人 resubmit 重跑次数，上限 3（P3 §9 防死循环；P3b 起财务不再 amend，计数器共用） |
| review_reasons | JSON | 复核原因列表（JacksonTypeHandler 映射，仅存储；重跑再次命中时刷新） |
| auditor_id | BIGINT | 最近处理人用户 ID（财务动作写；提交人 resubmit **不动**，防止申请人 ID 误写为审批人） |
| audit_comment | VARCHAR(512) | 最近处理意见 |
| created_by | BIGINT | 申请人用户 ID（任务提交人；resubmit/withdraw/withdrawRequest 权限校验依据） |
| created_at / updated_at / deleted | | |

## 15. audit_record 审批留痕表（append-only，审计溯源）

> 归属 agent-core。每次动作追加一条（含快照），记录操作人/角色/前后金额/意见/**变更前后数据快照**，不可更新不可删除（仅逻辑删除预留）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| ticket_id | BIGINT | 工单 ID，索引 `idx_ticket(ticket_id)` |
| action | VARCHAR(20) | 动作：SUBMIT 建单 / APPROVE 通过 / REJECT 驳回 / AMEND 提交人修改重跑 / TERMINATE 终止 / RERUN 重跑复位 / RERUN_FAILED 重跑失败复位 / WITHDRAW 提交人撤回 / WITHDRAW_REQ 发起撤销申请 / WITHDRAW_AGREE 同意撤销 / WITHDRAW_REFUSE 拒绝撤销 |
| before_amount | DECIMAL(12,2) | 变更前金额 |
| after_amount | DECIMAL(12,2) | 变更后金额 |
| before_data | JSON | 变更前数据快照（`ReimbursementService.buildSnapshot`；首条 SUBMIT 为 NULL） |
| after_data | JSON | 变更后数据快照（顶层字段 + 明细 + 附件引用，**不含 OSS 路径/预签名 URL**，日期转字符串） |
| comment | VARCHAR(512) | 操作意见 |
| operator_id | BIGINT | 操作人用户 ID（系统动作 NULL） |
| operator_name | VARCHAR(64) | 操作人姓名（系统动作 NULL） |
| operator_roles | VARCHAR(128) | 操作人当时角色（审计溯源；系统动作/提交人动作为 NULL） |
| created_at | DATETIME | 操作时间 |
| deleted | TINYINT | 逻辑删除 |

## 16. sys_permission 权限目录表（P3.5a 轻量资源级 RBAC）

> 归属 tenant-service。**平台级全局表，无 tenant_id**（所有租户共用同一套权限标识符）；权限码由迁移脚本种子定义（代码即目录），运行期不增删。⚠️ 查询必须走多租户拦截器 ignore 名单（common-mybatisplus-starter 已注册 `sys_permission`）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 种子固定主键 |
| perm_code | VARCHAR(64) UK | 权限标识符：系统管理操作级（`资源:操作`，如 user:create） / 业务资源级（如 reimb:viewAll） |
| perm_name | VARCHAR(64) | 权限名称（分配界面展示） |
| perm_type | VARCHAR(8) | MENU 菜单+接口 / API 仅接口 |
| group_name | VARCHAR(32) | 分组（系统管理/财务业务/预留，分配界面分区） |
| status | TINYINT | 1 启用 0 禁用 |
| created_at / updated_at | DATETIME | |

> 目录 v1（+P3.5c 工具码）：系统管理操作级 17 码（user:list/create/update/delete/assign-role、role:list/create/update/delete/assign-perm、dept:manage/create/update/delete、tenant:manage、tool:manage、tool:execute）+ 业务资源级 7 码（rule:manage、reimb/task/audit:viewAll、audit:approve、budget:viewAll）+ P4 预留 dashboard:admin。**`GET /api/v1/depts` 树查询不挂码**（报销选择器公用，读开写收）。

## 17. sys_role_permission 角色权限映射表（P3.5a）

> 归属 tenant-service。角色是权限的分配单位，分配为替换式（PUT 全量覆盖）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户 ID |
| role_id | BIGINT | 角色 ID，索引 `idx_role` |
| perm_id | BIGINT | 权限 ID（sys_permission.id），`uk_role_perm(tenant_id, role_id, perm_id)` |
| created_at | DATETIME | |
| deleted | TINYINT | 逻辑删除 |

## Seed 数据（脚本内置）

| 表 | 数据 |
|---|---|
| sys_tenant | 默认租户 `default`（id=1） |
| sys_role | `admin`（管理员）、`auditor`（审核员） |
| sys_user | `admin` / 密码 `admin123`（BCrypt） |
| sys_user_role | admin 绑定 admin 角色 |
| sys_permission | 权限目录 23 码种子（系统管理操作级 15 + 业务资源级 7 + 预留 1，P3.5a） |
| sys_role_permission | admin 全量 22 码；auditor 财务业务 5 码（rule:manage + 三个 viewAll + audit:approve）；普通用户不授码 |
| tool_registry | 预置 `amount_verify`（金额核验工具，含 JSON Schema）+ P2b 四个审核工具（ocr_extract/budget_query/rule_check/duplicate_check，scenario=FINANCE） |
| budget | 默认租户 2026-08 四个部门预算种子 |
| finance_rule | 四类规则种子（amount_limit/reimburse_expire/travel_standard/subsidy_limit，P2c 起 `published=1` 生效集） |

> ✅ P1.4 起 admin 密码为真实 BCrypt 哈希（`admin123` 明文仅存在于脚本注释，生产必改）；本机已执行定向 `UPDATE`，新环境整脚本执行即生效。
