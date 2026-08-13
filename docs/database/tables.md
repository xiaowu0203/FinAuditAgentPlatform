# FinAudit 数据库设计（P1）

> 目标库：`finaudit`（MySQL 5.7 / utf8mb4 / InnoDB）。完整建库脚本见 [`finaudit-schema.sql`](./finaudit-schema.sql)。

## 0. 通用约定

| 约定 | 说明 |
|---|---|
| 主键 | `id BIGINT AUTO_INCREMENT` |
| 多租户 | 业务表均含 `tenant_id BIGINT`（默认 1），配合 `TenantLineInnerInterceptor` 自动过滤 |
| 时间 | `created_at` / `updated_at DATETIME`，`ON UPDATE CURRENT_TIMESTAMP` |
| 逻辑删除 | 各表 `deleted TINYINT DEFAULT 0`（0 未删 / 1 已删） |
| 金额 | 一律 `DECIMAL(18,2)`，代码层用 `BigDecimal`，严禁 float/double |
| JSON 列 | 入参/结果/Schema 用 `JSON` 类型，MyBatis-Plus 以 `JacksonTypeHandler` 映射 |
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

## Seed 数据（脚本内置）

| 表 | 数据 |
|---|---|
| sys_tenant | 默认租户 `default`（id=1） |
| sys_role | `admin`（管理员）、`auditor`（审核员） |
| sys_user | `admin` / 密码 `admin123`（BCrypt） |
| sys_user_role | admin 绑定 admin 角色 |
| tool_registry | 预置 `amount_verify`（金额核验工具，含 JSON Schema） |

> ⚠️ admin 密码哈希当前为占位符 `__ADMIN_BCRYPT_PLACEHOLDER__`，P1.4 引入 `BCryptPasswordEncoder` 后生成真实哈希回填本脚本并重灌，或执行 `UPDATE sys_user SET password='...' WHERE username='admin'`。
