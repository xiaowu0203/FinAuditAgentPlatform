# 部署文档

> 目标：在一台干净机器上把 FinAuditAgentPlatform 跑起来（本机中间件 or docker-compose 二选一）。
> 相关：接口见 [`docs/api/`](../api/README.md)，数据库见 [`docs/database/`](../database/)，验收脚本见 [`docs/test/`](../test/README.md)，工程约定见 [`docs/architecture/conventions.md`](../architecture/conventions.md)。

## 1. 环境要求

| 组件 | 版本 | 端口 | 说明 |
|---|---|---|---|
| JDK | **21** | — | `backend/pom.xml` 的 `<java.version>21</java.version>` |
| Maven | 3.9+ | — | 多模块构建（`backend/` 下 19 个模块） |
| MySQL | **5.7** | 3306 | 勿升级（项目依赖 5.7 语义：无 `ADD COLUMN IF NOT EXISTS`、JSON 列约束更严） |
| Redis | 7.x | 6379 | 会话黑名单 / 权限快照 / 工具结果缓存 |
| Nacos | 3.2.2 | 8848（核心）+ 8080（控制台） | **3.x 前后端分离**，见 §3 |
| RabbitMQ | 3-management | 5672 / 15672 | 任务与工具执行的 MQ 编排 |
| MinIO | latest | 9000 / 9001 | 对象存储（附件） |
| Node.js | 20+ | 5173 | 仅前端开发需要（Vite 6） |
| docker（可选） | — | — | 只有走 `docker-compose.yml` 一键起中间件时才需要 |

> **本地开发推荐复用本机已装中间件**（AGENTS.md §9）；`docker-compose.yml` 仅用于开源环境复现，
> 两者都用标准端口，**不要同时启动**（会端口冲突）。

## 2. 环境变量与 `.env` 准备（⚠️ 本轮最大变更点）

```bash
cp .env.example .env
# 然后按下面的「必填」清单填好 .env
```

### 2.1 R7-1 变更：凭据类环境变量已取消 yml 回退，缺失即启动失败

**P3.8 R7-1 起，Nacos / RabbitMQ / MinIO / MySQL 的「凭据类」环境变量在 yml 中不再有回退默认值**
（原先回退的是 `nacos/nacos`、`guest/guest`、`minioadmin/minioadmin123`）。
缺失时服务**启动直接失败**，控制台报：

```
Could not resolve placeholder 'NACOS_PASSWORD' in value "${NACOS_PASSWORD}"
```

原因：旧版把「开发默认口令」直接写进 `application.yml` 当回退，等于把开发口令变成生产默认口令——
漏配一个环境变量不会报错，只会静默用弱口令连上去。

**「地址与端口类」变量仍保留本地拓扑缺省**（它们不是凭据，且本地开发固定），例如
`NACOS_SERVER_ADDR`（默认 `127.0.0.1:8848`）、`MYSQL_HOST`/`MYSQL_PORT`、`REDIS_HOST`/`REDIS_PORT`、
`RABBITMQ_HOST`/`RABBITMQ_PORT`、`MINIO_ENDPOINT`、`MINIO_BUCKET`。

### 2.2 变量清单

**凭据类 —— 必须显式设置（无回退）**

| 变量 | 被谁读取 | 备注 |
|---|---|---|
| `NACOS_USERNAME` / `NACOS_PASSWORD` | 五个服务的 Nacos 注册 + 配置中心；agent-core 的规则发布客户端 | 本机默认账号通常是 `nacos/nacos` |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 各服务数据源（经 **Nacos 共享配置** `common-datasource.yaml` 传递） | 见下 §2.3 的注意点 |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | agent-core-service、tool-service | ⚠️ `.env.example` 为 `admin/admin123456`，与 `docker-compose.yml` 的 `RABBITMQ_USER`/`RABBITMQ_PASS` **同名不同源**，本机自建时以你实际的 RabbitMQ 账号为准 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | **仅 file-service**（`finaudit.oss.enabled=true`，启动自检） | 其余服务不需要 MinIO 凭据 |
| `FINAUDIT_JWT_SECRET` | agent-gateway、tenant-service | HS256 要求 **≥32 字符**，`common-jwt-starter` 启动自检，过短直接失败 |
| `FINAUDIT_MODEL_API_KEY` | agent-core-service（经 Nacos `common-model-keys.properties` 注入） | 缺失时模型调用失败；默认 DeepSeek |

**地址/开关类 —— 有本地缺省，按需覆盖**

`NACOS_SERVER_ADDR`、`MYSQL_HOST`、`MYSQL_PORT`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`（本机无密码留空）、
`RABBITMQ_HOST`、`RABBITMQ_PORT`、`MINIO_ENDPOINT`、`MINIO_BUCKET`、`FINAUDIT_MODEL_BASE_URL`、`FINAUDIT_MODEL_NAME`、
`FINAUDIT_OCR_BAIDU_API_KEY`、`FINAUDIT_OCR_BAIDU_SECRET_KEY`（OCR 未配置时票据识别转人工，不阻断启动）、
`NACOS_AUTH_TOKEN`、`NACOS_CONSOLE_ADDR`（Nacos 初始化脚本用）。

### 2.3 ⚠️ `.env` 不会被 Spring Boot 自动读取

**Spring Boot 本身不读 `.env` 文件**。`.env.example` 注释已写明加载方式：

- **IDE**：用 EnvFile 插件加载项目根 `.env`（IntelliJ 推荐做法），或直接在 Run Configuration 里填环境变量；
- **命令行 / 容器 / CI**：由 shell（`set -a; source .env; set +a`）、systemd、docker `env_file` 等注入；
- **只 `cp .env.example .env` 而不做注入 = 服务照样启动失败**，这是最常见的「明明配了还是报 placeholder」的根因。

## 3. 初始化 Nacos

```bash
bash docs/deploy/nacos-init.sh      # 依赖 curl
```

- **前提**：本机 Nacos 已运行（核心服务 8848 + 控制台 8080），且 `NACOS_USERNAME`/`NACOS_PASSWORD` 可用；
- **作用**：创建 `dev` / `test` 两个命名空间，并向两者各发布 3 份共享配置占位：
  - `common-datasource.yaml`（MySQL 连接，值取自 `${MYSQL_*}` 占位）
  - `common-redis.yaml`（Redis 连接）
  - `common-model-keys.properties`（`finaudit.model.api-key=${FINAUDIT_MODEL_API_KEY}`）
- **幂等**：命名空间已存在则跳过；配置重复发布为覆盖；
- **密钥策略**：配置内容全是 `${ENV_VAR}` 占位，真实密钥只走环境变量，禁止入库。

> ⚠️ **服务侧是 `optional:nacos:...` 导入**（agent-core / tool-service / tenant-service / file-service 的
> `spring.config.import`）。「optional」只表示**配置缺失不报错**——但数据源各项会因此全部落空，
> 服务照样起不来。**所以第 3 步不能跳过**。
>
> ⚠️ **Nacos 配置内的凭据占位同样无回退**（R7 收尾）：`nacos-init.sh` 发布的
> `common-datasource.yaml` 里是 `username: ${MYSQL_USERNAME}` / `password: ${MYSQL_PASSWORD}`，
> **不带 `:root` 回退**——与 §2.1 的「yml 凭据无回退」同一口径，避免出现「一层严、一层松」的双闭环。
> 地址/端口类（`MYSQL_HOST`/`MYSQL_PORT`/`REDIS_HOST`/`REDIS_PORT`）仍保留本地缺省，它们不是凭据。
> `nacos-init.sh` **自身**登录 Nacos 用的 `NACOS_USERNAME/NACOS_PASSWORD` 保留 `nacos/nacos` 回退：
> 那是引导脚本的一次性登录（Nacos 出厂账号），且脚本不加载 `.env`；生产请在环境变量中覆盖。

### Nacos 3.x 说明（前后端分离）

Nacos 3.x 控制台已独立部署，初始化脚本适配此架构：

| 能力 | 地址 | 端点 |
|---|---|---|
| 登录获取 accessToken | 核心服务 8848 | `POST /nacos/v1/auth/login` |
| 命名空间管理 | 控制台 8080 | `/v3/console/core/namespace*` |
| 配置发布/查询/删除 | 控制台 8080 | `/v3/console/cs/config` |

控制台 API 统一路径格式：`/v3/console/[module]/[subPath]`，module 含 `server/cs/ns/core`。
鉴权默认开启，请求头携带 `accessToken: <token>`。
若控制台端口/上下文有修改，通过 `NACOS_CONSOLE_ADDR` 环境变量覆盖。

## 4. 初始化数据库

### 4.1 全新库 —— 用全量 schema

```bash
mysql -uroot -p < docs/database/finaudit-schema.sql
```

`finaudit-schema.sql` 是**全量快照**（建库表 + 全部种子数据 + `USE finaudit`），
**只用于全新库**；它会 `DROP TABLE IF EXISTS` 全部表，**在已有数据的库上执行会清空数据**。

> 🚨 **执行前务必确认目标是空库**（P3.8 R9 实测踩过：一次误操作清空了本地库，而本机 `log_bin=OFF` 无从恢复）：
> - 脚本内含 `USE finaudit;`，**命令行 `-D 其他库` 会被它覆盖**——想在别处验证请复制脚本并删掉 `USE`/`DROP` 段；
> - 本机无 binlog，**误执行不可恢复**；**动数据前先备份**（仓库自带一条命令的备份脚本）：
>   ```powershell
>   powershell -ExecutionPolicy Bypass -File docs\deploy\db-backup.ps1 `
>     -MySqlDumpExe "D:\mysql\mysql-5.7.10-winx64\mysql-5.7.10-winx64\bin\mysqldump.exe" -User root -Password root
>   # → backups\finaudit-<时间戳>.sql（backups/ 已 gitignore），并打印前后关键表行数便于比对
>   ```
> - 漏把新表加进脚本的 DROP 列表，会让重跑在第 N 张表处报 `Table 'xxx' already exists` 并**中途中断**，
>   留下「结构新、种子空」的半成品库（`sys_user` 为空 → 无法登录）。此时正确做法是**重新执行全量 schema**
>   （已修复 DROP 列表），而不是补跑增量脚本。

> 容器化环境无需手动执行：`docker-compose.yml` 已把该文件挂到
> `/docker-entrypoint-initdb.d/01-schema.sql`，MySQL 容器**首启**时自动执行
> （已有 `mysql-data` 数据卷时不会重复执行）。

### 4.2 已有库 —— 按文件名阶段顺序执行增量迁移

**不要**在已有库上跑全量 schema。已有库走增量迁移，**顺序即文件名里的阶段号**：

```bash
cd docs/database
mysql -uroot -p < migration-P2b.sql       # 1  OCR/预算/规则/重复检测四类工具 + tool_registry 种子
mysql -uroot -p < migration-P2c.sql       # 2  finance_rule 结构化 + 差旅/补贴种子
mysql -uroot -p < migration-P3a.sql       # 3  多 Agent 角色化（步骤 agent_role 等）
mysql -uroot -p < migration-P3b.sql       # 4  审批工单闭环（audit_ticket / audit_record）
mysql -uroot -p < migration-P3.5a.sql     # 5  RBAC：sys_permission / sys_role_permission + 权限码种子
mysql -uroot -p < migration-P3.5b.sql     # 6  部门实体 sys_dept + reimb/budget 的 dept_id 回填
mysql -uroot -p < migration-P3.5c.sql     # 7  P3.5c 安全加固
mysql -uroot -p < migration-P3.5d.sql     # 8  agent_task.started_at 等
mysql -uroot -p < migration-P3.8.sql      # 9  累计 R1~R6 增量（见下）
```

**幂等性（重要，别盲信「全部可重跑」）**

| 脚本 | 幂等 |
|---|---|
| `migration-P2b.sql` | ✅ `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` |
| `migration-P2c.sql` | ✅ `INSERT IGNORE` |
| `migration-P3a.sql` / `migration-P3b.sql` | ⚠️ **部分幂等**：两脚本内已用注释标出「仅可执行一次」的 `ALTER`（加列/加唯一键），**重跑会报 `Duplicate column name` / `Duplicate key name`**。其余段落可重复执行 |
| `migration-P3.5a.sql` | ✅ 固定主键 + `INSERT IGNORE` |
| `migration-P3.5b.sql` | ✅ `CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE` / `information_schema` 包裹 ALTER |
| `migration-P3.5c.sql` | ✅ `INSERT IGNORE` + 存在性判断包裹 ALTER |
| `migration-P3.5d.sql` | ✅ `information_schema` 存在性判断包裹 ALTER |
| `migration-P3.8.sql` | ✅ **累计脚本，每节幂等**，可整份重复执行（已实测重复执行无副作用）。MySQL 5.7 无 `ADD COLUMN IF NOT EXISTS`，故用 `information_schema` 判定后动态 DDL |

**`migration-P3.8.sql` 的 15 节与内部顺序约束**

| 节 | 内容 | 阶段 |
|---|---|---|
| 1 | 新增 `budget_occupancy` 占用记账表 | R1 |
| 2 | 核对：现有预算行（`used_amount` 语义自此开始真实累加） | R1 |
| 3 | 新增 `invoice_record` 发票标识符投影表 | R2 |
| 4 | 历史数据回填（从 `ocr_result` JSON 抽票号投影） | R2 |
| 5 | 回填报告：命中数 + 明细 | R2 |
| 6 | 新增 `invoice_reimb_link` 发票—报销单关联表 + 历史归属回填 | R3 |
| 7 | 工具注册：`invoice_match` | R3 |
| 8 | 核对：工具注册表最终状态（应含 `invoice_match`） | R3 |
| 9 | `audit_ticket` 增 `review_findings` 结构化问题项列 | R4 |
| 10 | 核对：工单表新列已就位 | R4 |
| 11 | `agent_task` 增 `correction_count` / `self_check_result` | R5 |
| 12 | 核对：`agent_task` 自校验两列已就位 | R5 |
| 13 | `tool_registry` 增 `output_schema` 出参 Schema 列 | R6 |
| 14 | 工具契约对称化：`budget_query` 入参 Schema + `amount_verify` 出参 Schema | R6 |
| 15 | 核对：出参 Schema 列与两条契约更新已就位 | R6 |

> ⚠️ 脚本内**自带顺序约束**（脚本头部注释）：①③ 必须先于 ④（回填依赖表已建出）；
> ⑥ 必须先于 ⑦/⑧ 的核对；
> **第 13 节必须在【重启 tool-service】之前完成**——因为 `tool-service` 执行工具前会按 `tool_code`
> 查 `tool_registry`，查不到即抛「工具未注册或已禁用」，流水线到该步直接失败。

**逐条核对建议**：每节都有「核对」段（纯 `SELECT`），执行完看一眼输出是否符合预期（如 `tool_registry` 是否含 `invoice_match`）。

迁移完成后，库内应有 **21 张表**（`finaudit-schema.sql` 之外的增量表全部建出）。

## 5. 启动顺序与端口

**先中间件，再后端服务，最后前端。**

```bash
# 中间件（三选一）
docker compose up -d                     # 方式 A：一键起 Nacos/MySQL/Redis/RabbitMQ/MinIO
#   或：手工启动本机已装中间件（推荐本地开发）
#   或：外部已有中间件 → 用 .env 指过去

# 后端（在 backend/ 目录；建议顺序如下）
cd backend
mvn -q clean install -DskipTests        # 首次（或改了 common/* starter 后）必须先 install：
                                        # 各服务依赖本地仓库里的 starter 构件，-pl 单模块跑不起来

mvn spring-boot:run -pl agent-gateway       # 9080  网关（鉴权/路由/注入身份头）
mvn spring-boot:run -pl tenant-service      # 9203  租户/用户/角色/部门/权限/JWT 签发
mvn spring-boot:run -pl agent-core-service  # 9201  Agent 调度 + 报销单 + 审批工单 + 规则
mvn spring-boot:run -pl tool-service        # 9202  工具注册表 + 执行器
mvn spring-boot:run -pl file-service        # 9205  文件上传/下载/预览
mvn spring-boot:run -pl rag-service         # 9204  RAG 骨架（P4 填充；⚠️ 网关未配路由，外部不可达）

# 前端（在 frontend/ 目录）
cd frontend && npm install && npm run dev   # http://localhost:5173（dev 代理 /api → 网关 9080）
# 生产构建：npm run build（含 vue-tsc -b 类型检查）→ dist/
```

**为什么是这个顺序**：`tenant-service` 是登录与权限快照的来源（网关要读它写的 Redis 快照），
`tool-service` / `file-service` 被 `agent-core` 经 Feign 调用，`rag-service` 当前无人依赖、可最后起。

**端口速查**

| 服务 | 端口 | 网关路由前缀 |
|---|---|---|
| `agent-gateway` | 9080 | —（统一入口） |
| `agent-core-service` | 9201 | `/api/v1/tasks/**`、`/api/v1/reimbursements/**`、`/api/v1/audit/tickets/**`、`/api/v1/rules/**` |
| `tool-service` | 9202 | `/api/v1/tools/**` |
| `tenant-service` | 9203 | `/api/v1/auth/**`、`/api/v1/users/**`、`/api/v1/tenants/**`、`/api/v1/roles/**`、`/api/v1/permissions/**`、`/api/v1/depts/**` |
| `rag-service` | 9204 | **无路由（经网关不可达）** |
| `file-service` | 9205 | `/api/v1/files/**` |
| 前端 dev server | 5173 | 代理 `/api` → `http://localhost:9080` |

> `/internal/**` 是**服务间契约前缀，故意不配网关路由**（外部不可达）。新增内部端点**不要**在网关加路由。

**种子账号**：`admin / admin123`（租户 `default`，角色 `admin`，持有全量权限码）。

**前端覆盖网关地址**：`frontend/.env` 里设 `VITE_GATEWAY_ORIGIN`（默认 `http://localhost:9080`）。

## 6. 常见故障排查

### 6.1 启动报 `Could not resolve placeholder 'NACOS_PASSWORD'`（或 `RABBITMQ_PASSWORD` / `MINIO_ACCESS_KEY` / `FINAUDIT_JWT_SECRET`）

**根因**：`.env` 没被加载进进程环境，或该变量在 `.env` 里缺项。
P3.8 R7-1 之后这些凭据类变量**没有任何 yml 回退值**，缺失即启动失败（见 §2.1）。

**排查**

1. **确认进程真的拿到了变量**（最有效的一步）：
   - IDE：检查 EnvFile 插件是否启用、路径是否指向项目根 `.env`；或直接在 Run Configuration → Environment variables 里填；
   - 命令行：`echo $NACOS_PASSWORD`（PowerShell：`$env:NACOS_PASSWORD`）应为非空。
2. **确认只 `cp` 了没注入**：`cp .env.example .env` 只是造了个文件，Spring Boot **不读**它（§2.3）。
3. 检查变量名拼写与 `.env.example` 完全一致（大小写敏感，`NACOS_PASSWORD` 不是 `Nacos_Password`）。

**修法**：把 `.env` 内容注入进程环境，或直接在启动命令前导出，例如
PowerShell：`$env:NACOS_PASSWORD='nacos'; mvn spring-boot:run -pl tenant-service`。

### 6.2 服务起来了但数据源连不上 / 报找不到 `spring.datasource.url`

**根因**：跳过了 §3 的 Nacos 初始化，`common-datasource.yaml` 不存在。
四个业务服务都是 `optional:nacos:common-datasource.yaml`——**optional 只保证不因「配置缺失」报错，不代表数据源有值**。

**修法**：跑 `bash docs/deploy/nacos-init.sh`，并确认命名空间是 `dev`（各服务 `spring.cloud.nacos.config.namespace: dev`）。

### 6.3 工具步骤必然失败：`工具未注册或已禁用: xxx`

**根因**：`tool_registry` 里没有该 `tool_code`（新工具必须三件事齐全：① `ToolCode` 枚举 → ② `ToolExecutor` 实现 → ③ **注册表注册**）。

**修法**：确认已执行 `migration-P3.8.sql` 第 7 节（注册 `invoice_match`）与第 13 节（`output_schema` 等），再看 `SELECT tool_code, enabled FROM tool_registry;`。

### 6.4 任务一直停在 `RUNNING` 不推进

排查顺序：

1. **RabbitMQ 是否连通**：agent-core / tool-service 的控制台有无 MQ 连接异常；管理台 http://localhost:15672 看队列堆积；
2. **消费者并发仅为 1**（两服务 `listener.simple.concurrency: 1`）：一次 OCR 网络挂起会占死 TOOL 消费线程，**全租户任务停止推进**。
   已给 OCR 与附件下载补了超时（默认 10s），若仍出现请查该服务的 WARN 日志；
3. **`tool.result` 是否回吐**：失败路径也必须发 `tool.result(success=false)`，否则只能等任务级超时（默认 30 分钟，`finaudit.agent.task-timeout-minutes`）；
4. 查 `finaudit.dlq` 队列与告警日志（P3.8 R7-8 起有 DLQ 消费告警）。

### 6.5 登录报 500 / 前端登录失败，tenant-service 抛 `StackOverflowError`

**根因（历史坑，已修）**：`RedissonConnectionFactory` 顶掉了 Boot 默认的 Lettuce 连接工厂，
而 `redisson-spring-data-34` 不适配 `spring-data-redis 3.5.0`，在 `DefaultedRedisConnection.pExpire` 上无限递归。
当前依赖已改为只引 `redisson` 核心包（仅提供 `RedissonClient` 做分布式锁），连接工厂交回 Lettuce。

**修法**：若在依赖里看到 `redisson-spring-boot-starter` 或 `redisson-spring-data-34`，说明依赖树被污染，
执行 `mvn -q dependency:tree -pl common/common-redis-starter` 核对。

### 6.6 网关 401 / 403

| 现象 | 含义 | 排查 |
|---|---|---|
| **401** + `{"code":401,...}` | 缺 token / token 无效或过期 / 已登出（jti 黑名单）/ 被踢下线（`blackver`） | 重新登录；确认请求带 `Authorization: Bearer <token>` |
| **403** + `{"code":403,"message":"无权限访问"}` | `@RequirePerm` 未通过（权限码不在快照里）或上下文缺失 | 看该端点要求的权限码；确认登录后 Redis 里有 `finaudit:auth:snapshot:{userId}`；`admin` 持有全量权限码 |

> ⚠️ **业务错误一律 HTTP 200**，错误码在 body 的 `code`——不要只看 HTTP 状态。详见 [`docs/api/README.md`](../api/README.md) 第「错误语义」。

### 6.7 附件区空白 / 上传被拒

- **附件区静默空白**：历史上由「内部代读他人附件被用户可见性校验拒绝」引起，现内部链路走 `/internal/files/**`（网关不暴露）。
  若复现，先确认 file-service 已是最新代码，再看其日志有无 `无权访问该文件`；
- **上传被拒**：单文件上限 **20MB**（`spring.servlet.multipart.max-file-size`）。超限有两条处理路径：
  ① 容器层先拦 → `FileExceptionHandler` 捕获 `MaxUploadSizeExceededException`，返回 **HTTP 200 + `code=400`「文件大小超出限制」**；
  ② 进到应用层（如 `max-request-size` 与单文件上限不一致的边界）→ `FileService.upload` 再校验一次，文案更具体：
  「文件大小不能超过 20MB（当前 X MB）」。两条都是标准 `R<T>`，前端按 `code` 判断即可（已实测确认）；
- **`缺少租户标识 X-Tenant-Id`**：说明请求没走网关（直连服务端口）。对外端点一律 fail-closed，**不支持直连**。

### 6.8 前端请求 404 / 跨域

- **404**：检查路径是否在 §5 的网关路由前缀内。`/api/v1/audit/**` 已**收窄为 `/api/v1/audit/tickets/**`**，
  其余审核数据端点迁到 `/internal/audit/**`（外部不可达）；
- **跨域**：`docker-compose` 与 dev server 场景下网关已对 `/**` 放开 CORS（`allowedOrigins: "*"`，仅供开发）；
  生产必须收敛为具体域名；
- **前端 dev 首几次点菜单整页刷新**：Element Plus 按需引入 + 动态路由导致的 dev 预构建问题，已在 `vite.config.ts` 用自动推导 `optimizeDeps.include` 修复；若复现，删 `node_modules/.vite` 重启。

## 7. 验证部署是否成功

```bash
# 1. 登录（经网关）
curl -X POST http://localhost:9080/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin123"}'
#    预期：HTTP 200，body 里 code=0，data.token 非空，data.user.perms 为权限码数组

# 2. 带 token 拉任务列表
curl http://localhost:9080/api/v1/tasks -H "Authorization: Bearer <token>"
```

完整的端到端验收脚本见 [`docs/test/README.md`](../test/README.md)。

## 8. 待补充

- Kubernetes / 生产编排（当前只有 `docker-compose.yml` 覆盖中间件，**不含后端服务镜像**）
- 中间件启停脚本、日志采集与告警接入
- P4：Milvus（rag-service）、监控大盘
