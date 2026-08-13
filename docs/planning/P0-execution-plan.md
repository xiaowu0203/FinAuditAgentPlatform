# P0 基建阶段 · 执行点文档

> 版本: v0.1 ｜ 状态: 待确认 ｜ 前置依赖: 无
> 目标: 一个**可运行、可编译、可提交双 remote** 的工程空骨架 + 本地中间件就绪

---

## 0. 决策点（已确认）

| # | 决策 | 结论 | 状态 |
|---|---|---|---|
| D0 | 包名 / groupId | `com.finaudit`（包名 `com.finaudit.*`） | ✅ 已定 |
| D1 | MySQL / Redis / MinIO 部署方式 | **复用本机已装**；docker-compose 仍提供，仅用于开源环境复现 | ✅ 已定 |
| D2 | 默认分支 | `main` | ✅ 已定 |

## 1. 版本矩阵（锁定，写入 CLAUDE.md）

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | 21 | |
| Spring Boot | 3.5.0 | |
| Spring Cloud | 2025.0.0 | |
| Spring Cloud Alibaba | 2025.0.0.0 | aigc4lk 实测与 Boot3.5 兼容 |
| Spring AI | 1.1.2 | P1 接入，P0 先锁版本 |
| MyBatis-Plus | 3.5.12 | 用 `mybatis-plus-spring-boot3-starter` |
| Nacos | 3.2.2 | 本机已有，复用 |
| RabbitMQ | 3-management | 本机已有，复用 |
| MySQL | 5.7 | 用户指定不升级 |
| Redis | 7.x | 未定，D1 确认 |
| MinIO | RELEASE.latest | D1 确认 |
| Milvus | — | 延后到 P2，不参与 P0 |
| Redisson | 3.4x | P1 分布式锁，P0 锁版本 |

## 2. 仓库与目录初始化

1. `git init`，默认分支 `main`，**暂不 push**
2. 关联双 remote：`origin-gitee` + `origin-github`（Gitee 镜像同步到 GitHub，或双 push 脚本）
3. 根 `.gitignore`：`.idea/`、`backend/**/target/`、`frontend/node_modules/`、`*.log`、`.env`、密钥文件、`docker/data/`
4. 目录骨架（按需求文档四章）：`backend/`、`frontend/`（占位）、`docs/`（architecture/database/api/deploy/test/planning）
5. 根 `README.md` + `README.en.md` 占位（P5 补全）
6. `CLAUDE.md`：版本矩阵 + 目录规范 + 代码规范 + Git 规范 + **禁止提交密钥条款**

**验收**: `git status` 干净；`git remote -v` 显示两个 remote

## 3. Maven 多模块结构

```
backend/
├── pom.xml                  # 父 POM：dependencyManagement 统一版本，packaging=pom
├── agent-gateway/           # 网关（P0 仅骨架 + 注册 Nacos 验证）
├── common/                  # 聚合父模块
│   ├── common-code/                # 全局异常/统一返回R<T>/JSR303/跨域
│   ├── common-redis-starter/        # RedisTemplate + 分布式锁（P0 骨架）
│   ├── common-mybatisplus-starter/  # MyBatis-Plus + 分页 + 多租户插件位
│   ├── common-model-starter/        # 多模型抽象接口（P1 实现 DeepSeek）
│   └── common-trace-starter/        # traceId 生成/透传（P0 骨架）
├── tenant-service/          # P1 建，P0 不建
├── agent-core-service/      # P1
├── rag-service/             # P2
├── tool-service/            # P1
└── task-job-service/        # P4
```

P0 只建：父 POM + `common` 各子模块骨架 + `agent-gateway` 最小可启动服务。

**验收**: `mvn clean install` 全量编译通过（不含 P1+ 模块）

## 4. common 骨架内容（P0 建空壳，P1 补逻辑）

| Starter | P0 交付 | P1 补全 |
|---|---|---|
| common-code | 统一返回 `R<T>`、全局异常处理器、参数校验 | 日志审计切面、操作日志 |
| common-redis-starter | 配置类 + 自动装配骨架 | 分布式锁、上下文缓存 |
| common-mybatisplus-starter | 配置 + 分页插件 | 多租户插件、逻辑删除 |
| common-model-starter | `ChatClientFactory` 接口 + 模型枚举 | DeepSeek/Qwen/Claude 实现、故障切换、token 统计 |
| common-trace-starter | `traceId` 生成过滤器 | 全链路透传（配合 SkyWalking P4） |

每个 starter 必须带 `spring.factories` / `AutoConfiguration.imports` + README 说明。

## 5. docker-compose.yml（一键中间件）

| 服务 | 镜像 | 端口 | 备注 |
|---|---|---|---|
| nacos | `nacos/nacos-server:v3.2.2` | 8848/9848 | standalone，D1 若复用本机则注释 |
| mysql | `mysql:5.7` | 3306 | 需挂数据卷 + init SQL 目录 |
| redis | `redis:7-alpine` | 6379 | |
| rabbitmq | `rabbitmq:3-management` | 5672/15672 | D1 若复用本机则注释 |
| minio | `minio/minio` | 9000/9001 | + `mc` 初始化容器建 bucket |

提供：数据卷持久化、健康检查、`.env` 模板（密码不入库）。

**验收**: `docker compose up -d` 后 `docker compose ps` 全部 healthy

## 6. Nacos 初始化

- 命名空间：`dev` / `test` 两个（环境隔离，需求硬性要求）
- 共享配置 data-id 占位：`common-datasource.yaml`、`common-redis.yaml`、`common-model-keys.properties`（**只存占位，密钥走环境变量**）
- 初始化脚本：`docs/deploy/nacos-init.sh`（用 Nacos Open API 创建命名空间 + 发布配置）

## 7. 代码规范要点（写入 CLAUDE.md）

1. 统一包名（D0）、统一异常、统一返回
2. Controller/Service/Mapper 分层命名规范
3. 注释规范（Starter 模块必须有模块级 README + 关键类注释）
4. **密钥/密码禁止入库**，一律环境变量 + `.env.example`
5. Git 提交信息规范（`feat/refactor/fix/docs` 前缀）

## 8. 验收清单（P0 完成定义）

- [x] `git init` + 双 remote 关联，无遗留 .idea 入库
- [x] `CLAUDE.md` 版本矩阵齐全
- [x] `mvn clean install` 全量编译通过（JDK 21，7 模块 BUILD SUCCESS）
- [x] `agent-gateway` 启动成功且**注册到 Nacos**（`nacos registry ... agent-gateway 172.26.144.1:9080 register finished`）
- [x] Nacos `dev`/`test` 命名空间 + 3 个共享配置占位发布（`docs/deploy/nacos-init.sh` 已执行并验证，适配 Nacos 3.x 控制台 API）
- [x] 根 `.gitignore` 生效，无 target/密钥误提交
- [~] `docker compose up` 中间件健康：本机复用模式，MySQL/Redis/RabbitMQ 端口 OPEN；**MinIO 未运行**（P2 前需启动）
- 备注：本机 8080 被 Nacos console 占用，网关端口定为 **9080**

## 9. 交付物清单

- `docker-compose.yml`、`.env.example`
- `backend/` 多模块骨架（父 POM + 5 个 starter 空壳 + gateway 最小服务）
- `CLAUDE.md`、`README.md`/`README.en.md` 占位
- `docs/deploy/nacos-init.sh`、环境盘点清单

## 10. 风险与规避

| 风险 | 规避 |
|---|---|
| Nacos 3.2.2 与本机 Spring Cloud Alibaba 兼容性 | 用 aigc4lk 验证过的组合，P0 用最小服务实测 |
| MySQL 5.7 与 MyBatis-Plus 3.5.x 兼容 | 低风险，P1 再实测 |
| 端口冲突（8848/3306/6379 等） | 环境盘点先查占用 |
| 包名/groupId 后期难改 | D0 开工前必须定 |

## 11. P0 结论与 P1 交接（2026-08-13）

**P0 状态：✅ 完成**。交付物已提交并同步双 remote（`gitee` + `github`），commit 见 `git log`。

**P0 期间发现的环境事实（P1 起必须沿用）：**
- 本机 Nacos 3.2.2 为**前后端分离部署**：核心服务 8848 + 控制台独立 8080。初始化/运维脚本必须走 3.x 控制台 API（`/v3/console/...`），详见 `docs/deploy/README.md`。这也是 8080 被占用的原因，网关端口定为 **9080**。
- Nacos 初始化脚本 `docs/deploy/nacos-init.sh` 已适配并实测：dev/test 命名空间 + 3 个占位配置（datasource/redis/model-keys），密钥全部 `${ENV_VAR}` 占位。
- 本机 Nacos 中 `research` 命名空间属于其他项目（AgentScope），**禁止改动**。

**P0 遗留事项（进入 P1 时注意）：**
- MinIO 本机未启动（9000/9001 关闭），P2（file/RAG）前需启动，P1 暂不依赖。
- 网关 discovery locator 已开（`agent-gateway` 9080 已注册），P1 将补路由规则与鉴权。

**P1 交接范围（待新会话设计执行点并经用户确认）：**
`agent-gateway`（路由）→ `tenant-service`（租户/用户/权限）→ `agent-core-service`（单 Agent + 状态机 + 任务持久化 + MQ 编排）→ `tool-service`（工具注册/执行），DeepSeek 接入，最小前端。数据库建库脚本 `finaudit`（MySQL 5.7）。
