# FinAudit 数据库设计（P2a-重构）

> 目标库：`finaudit`（MySQL 5.7 / utf8mb4 / InnoDB）。完整建库脚本见 [`finaudit-schema.sql`](./finaudit-schema.sql)。

## 0. 通用约定

| 约定 | 说明 |
|---|---|
| 主键 | `id BIGINT AUTO_INCREMENT` |
| 多租户 | 业务表均含 `tenant_id BIGINT`（默认 1），配合 `TenantLineInnerInterceptor` 自动过滤 |
| 时间 | `created_at` / `updated_at DATETIME`，`ON UPDATE CURRENT_TIMESTAMP` |
| 逻辑删除 | 各表 `deleted TINYINT DEFAULT 0`（0 未删 / 1 已删） |
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
| status | TINYINT | 1 启用 / 0 禁用 |
| created_at / updated_at / deleted | | |

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
| total_steps | INT | 总步骤数 |
| finished_steps | INT | 已完成步骤数 |
| result | JSON | 最终结果（汇总 JSON） |
| error_msg | VARCHAR(1024) | 失败原因 |
| created_by | BIGINT | 提交人用户 ID |
| created_at / updated_at / deleted | | 索引 `idx_tenant_status(tenant_id, status)`、`idx_created_at` |

**任务状态机**：`PENDING → RUNNING → SUCCESS / FAILED`（预留 `MANUAL_REVIEW` 人工复核）。

## 6. agent_task_step Agent 任务步骤表（断点续跑载体）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | |
| task_id | BIGINT | 任务 ID，UK(task_id, step_no) |
| step_no | INT | 步骤序号（从 1 开始） |
| step_name | VARCHAR(64) | 步骤名称 |
| step_type | VARCHAR(16) | `LLM`（模型推理）/ `TOOL`（工具调用） |
| tool_name | VARCHAR(64) | TOOL 步骤的工具编码 |
| input_params | JSON | 步骤入参 |
| output | JSON | 步骤输出 |
| status | VARCHAR(20) | 步骤状态 |
| error_msg | VARCHAR(1024) | 失败原因 |
| retry_count | INT | 重试次数 |
| created_at / updated_at / deleted | | 索引 `idx_task(task_id)`、`idx_status(status)` |

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
| dept_name | VARCHAR(64) | 部门（D6：先字符串，独立部门表后置） |
| total_amount | DECIMAL(12,2) | 申报总金额（服务端按明细求和，不信任客户端） |
| task_id | BIGINT | 关联 `agent_task.id`（提交后经 Feign 反写） |
| status | VARCHAR(20) | 审核状态，对齐任务状态机 |
| claim_date | DATE | 报销日期 |
| remark | VARCHAR(512) | 备注 |
| items | JSON | 报销明细 `[{name,amount,amountType,quantity,unitPrice,date}]` |
| created_at / updated_at / deleted | | 索引 `uk_reimb_no`、`idx_tenant_status(tenant_id,status)`、`idx_applicant(tenant_id,applicant_id)`、`idx_task(task_id)` |

**状态机**：`PENDING → RUNNING → SUCCESS / FAILED`（预留 `MANUAL_REVIEW`），与任务状态机对齐。

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
| file_name | VARCHAR(255) | 原始文件名 |
| object_name | VARCHAR(255) | **对象存储 key（含租户前缀 `{tenantId}/{yyyyMM}/{uuid}{ext}`，防跨租户碰撞）** |
| content_type | VARCHAR(128) | MIME 类型，默认 `application/octet-stream` |
| size | BIGINT | 字节大小 |
| created_at / updated_at / deleted | | 索引 `idx_tenant(tenant_id)` |

## Seed 数据（脚本内置）

| 表 | 数据 |
|---|---|
| sys_tenant | 默认租户 `default`（id=1） |
| sys_role | `admin`（管理员）、`auditor`（审核员） |
| sys_user | `admin` / 密码 `admin123`（BCrypt） |
| sys_user_role | admin 绑定 admin 角色 |
| tool_registry | 预置 `amount_verify`（金额核验工具，含 JSON Schema） |

> ✅ P1.4 起 admin 密码为真实 BCrypt 哈希（`admin123` 明文仅存在于脚本注释，生产必改）；本机已执行定向 `UPDATE`，新环境整脚本执行即生效。
