# FinAuditAgentPlatform 开发规范

> 本文件是开发者（含 AI 助手）的强制性约定，改动需评审。

## 1. 项目定位

财务费用智能审核 Agent 平台。Spring Cloud 分布式微服务 + Spring AI 构建，最终开源到 Gitee + GitHub。核心是**自主任务拆解、分步执行、工具联动、自主纠错**，拒绝问答 Demo。

## 2. 技术栈版本矩阵（锁定，勿擅自升级）

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Spring Boot | 3.5.0 |
| Spring Cloud | 2025.0.0 |
| Spring Cloud Alibaba | 2025.0.0.0 |
| Spring AI | 1.1.2 |
| MyBatis-Plus | 3.5.12（`mybatis-plus-spring-boot3-starter`） |
| Nacos | 3.2.2（本机已有） |
| RabbitMQ | 3-management（本机已有） |
| MySQL | 5.7（本机已有，勿升级） |
| Redis | 7.x（本机已有） |
| MinIO | latest（本机已有） |
| Milvus | P2 引入 |
| Redisson | 3.47.0 |

## 3. 包名规范

- groupId / 根包名：`com.finaudit`
- 微服务：`com.finaudit.<module>`（如 `com.finaudit.gateway`、`com.finaudit.tenant`）
- 公共 Starter：`com.finaudit.starter.<name>`（如 `com.finaudit.starter.web`）

## 4. 目录结构（强制）

```
backend/                微服务多模块 Maven 工程
├── agent-gateway       网关
├── tenant-service      租户/用户/权限
├── agent-core-service  Agent 调度/多智能体/任务
├── rag-service         知识库/RAG（含 file 能力）
├── tool-service        工具执行/注册
├── task-job-service    定时任务
└── common-starter      自定义 Starter 聚合
    ├── common-web-starter
    ├── common-redis-starter
    ├── common-mybatisplus-starter
    ├── common-model-starter
    └── common-trace-starter
frontend/               Vue3 前端
docs/                   全套文档
docker-compose.yml      开源环境一键启动
```

微服务数量上限 6（网关不计入），禁止盲目拆分。

## 5. 代码规范

1. 分层命名：`controller / service / mapper / entity / dto / vo`
2. 统一返回 `R<T>`、全局异常、JSR303 参数校验（由 common-web-starter 提供）
3. 金额计算**必须 Decimal**，严禁 float/double
4. 每个 Starter 必须带模块 README + 关键类注释
5. Controller 只做参数装配与返回，业务放 Service
6. 禁止在代码里硬编码密钥/数据库密码

## 6. Git 规范

- 远程双仓：`gitee`（镜像主仓）+ `github`，两仓内容一致
- 提交信息：`feat: / fix: / refactor: / docs: / test: / build:` 前缀 + 中文说明
- **密钥、数据库密码、token 一律走环境变量 + `.env.example`，禁止提交**
- 提交前检查：`git status` 无 target/.idea/.env 误入
- 每个阶段完成后由用户确认再提交推送

## 7. 环境信息

- 本地开发**复用本机中间件**（Nacos:8848 / RabbitMQ:5672 / MySQL:3306 / Redis:6379 / MinIO:9000）
- docker-compose.yml 仅用于开源环境复现，不与本机连接冲突
- 模型默认 DeepSeek，密钥走环境变量 `FINAUDIT_MODEL_API_KEY`
