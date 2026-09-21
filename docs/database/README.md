# 数据库（docs/database）

本目录是**数据库的唯一权威来源**：建表 DDL、种子数据、按阶段的增量迁移脚本，以及表结构说明。
所有脚本均以 **MySQL 5.7** 为目标（无 `ADD COLUMN IF NOT EXISTS`、无递归 CTE、无 RLS）。

## 1. 文件清单

| 文件 | 用途 |
|---|---|
| [`finaudit-schema.sql`](./finaudit-schema.sql) | **全量建库脚本**：21 张表 + 内置种子。仅用于**全新库** |
| `migration-P2b / P2c / P3a / P3b / P3.5a~d / P3.8 .sql` | **增量迁移脚本**：已有库按 §2 顺序**逐个**执行 |
| [`tables.md`](./tables.md) | 表结构说明（字段语义、索引、关联关系），与 `finaudit-schema.sql` 同步维护 |

## 2. 执行顺序（重要）

### 2.1 全新库
```bash
mysql -uroot -p < docs/database/finaudit-schema.sql
```
全量脚本已含各阶段迁移的最终形态与全部种子，**不要**再逐个跑 `migration-*.sql`。

### 2.2 已有库：按阶段先后逐个执行
```
migration-P2b.sql → migration-P2c.sql → migration-P3a.sql → migration-P3b.sql
  → migration-P3.5a.sql → migration-P3.5b.sql → migration-P3.5c.sql → migration-P3.5d.sql
  → migration-P3.8.sql        ← 最新（P3.8 全部增量，共 15 节）
```
建议显式指定库名逐个执行，便于定位失败节：
```bash
mysql -uroot -p --default-character-set=utf8mb4 -D finaudit -e "source F:/path/to/migration-P3.8.sql"
```

### 2.3 ⚠️ 幂等性并不一致，别当成「可以随便重跑」

下表按**脚本内标注 + 是否用 `information_schema` 守卫动态 DDL + 实测**给出（不是"看起来应该幂等"）：

| 脚本 | 幂等 | 依据 |
|---|---|---|
| `migration-P2b.sql` | ❌ | 1 处 `ALTER TABLE` 无守卫，脚本自注「仅一次」2 处 → 重跑报 `Duplicate column name` |
| `migration-P2c.sql` | ❌ | 2 处 `ALTER TABLE` 无守卫（含 `deleted` 列扩容），重跑报错 |
| `migration-P3a.sql` | ❌ | 1 处 `ALTER TABLE` 加执行角色列，无守卫 |
| `migration-P3b.sql` | ❌ | 4 处 `ALTER` 无守卫，脚本自注「仅一次」5 处（快照列、`uk_task_step` 重构等） |
| `migration-P3.5a.sql` | ⚠️ | 无 `ALTER`，建表 `IF NOT EXISTS` + 种子 `INSERT IGNORE`；仅「`sys_user_role` 历史清理」一条为一次性语义（重跑无副作用） |
| `migration-P3.5b.sql` | ✅ | 6 处 `information_schema` 判定 + `PREPARE/EXECUTE` 动态 DDL；回填带条件 |
| `migration-P3.5c.sql` | ✅ | 1 处动态 DDL 守卫 + `INSERT IGNORE` 种子 |
| `migration-P3.5d.sql` | ✅ | 2 处动态 DDL 守卫 |
| **`migration-P3.8.sql`** | ✅ | 9 处 `information_schema` 守卫 + `INSERT IGNORE` + 条件回填；**已实测重复执行通过** |

> 判读口诀：报 `Duplicate column name` / `Duplicate key name` 通常意味着**该节此前已执行成功**，
> 按报错定位到具体节确认即可，不需要回滚；P3.8 则可以随时重跑。

## 3. MySQL 5.7 写法约定（本项目踩过的坑）

| 约定 | 原因 |
|---|---|
| 加列/加索引用 `information_schema` 判定 + `PREPARE/EXECUTE` 动态 DDL | 5.7 无 `ADD COLUMN IF NOT EXISTS`，直接 ALTER 一重跑就报错 |
| 逻辑删除统一 `deleted`（`0`=未删 / **主键 id**=已删，类型 `BIGINT`） | 含 `deleted` 的唯一键才能让「重规划后重插」不撞历史行；`TINYINT` 存 id 会溢出 |
| JSON 列的更新**必须经实体更新**，禁用 `LambdaUpdateWrapper.set(列, 非null值)` | wrapper 参数不带 typeHandler，驱动按 binary 字符集发送 → MySQL 拒绝：`Cannot create a JSON value from a string with CHARACTER SET 'binary'`（详见 [`conventions.md`](../architecture/conventions.md) §2.2） |
| 金额一律 `DECIMAL` | 严禁 float/double |
| 多租户表带 `tenant_id`，由 `TenantLineInnerInterceptor` 自动注入条件 | 自定义 XML SQL 须加 `@InterceptorIgnore(tenantLine="true")` 并自行带租户条件 |
| 权限目录 `sys_permission` **不做租户隔离** | 平台级目录；租户差异由 `sys_role_permission`（有 `tenant_id`）承载 |

## 4. 与代码的配合：先迁移、后重启

实体新增字段后**必须先执行迁移再重启服务**——MyBatis-Plus 会把实体所有字段带进 SQL，
DB 缺列时直接报 `Unknown column`，且是**启动后首次访问**才暴露（甚至更晚）。
已有先例：`agent_task.correction_count/self_check_result`（R5）、`audit_ticket.review_findings`（R4）、
`tool_registry.output_schema`（R6-4）、`invoice_reimb_link`（R3）。

完整交付顺序、`.env` 准备与故障排查见 [`docs/deploy/README.md`](../deploy/README.md)。
