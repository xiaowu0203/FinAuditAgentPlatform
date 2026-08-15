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
| jjwt | 0.12.6（JWT 签发/解析，网关与 tenant-service 共用） |
| Nacos | 3.2.2（本机已有） |
| RabbitMQ | 3-management（本机已有） |
| MySQL | 5.7（本机已有，勿升级） |
| Redis | 7.x（本机已有） |
| MinIO | latest（本机已有） |
| Milvus | P2 引入 |
| Redisson | 3.47.0 |
| AWS SDK v2 | 2.53.1（common-oss-starter：MinIO/腾讯云 COS 统一 S3 客户端） |

## 3. 包名规范

- groupId / 根包名：`com.finaudit`
- 微服务：`com.finaudit.<module>`（如 `com.finaudit.gateway`、`com.finaudit.tenant`）
- 公共 Starter：`com.finaudit.starter.<name>`（如 `com.finaudit.starter.web`）
- 数据模型统一归 `pojo` 父包：`pojo.entity` / `pojo.dto` / `pojo.vo` 三个独立包，**dto 与 vo 禁止混包**（请求体→dto，响应体→vo）
- 枚举统一放模块级 `enums` 包（与 `pojo` 同级）

## 4. 目录结构（强制）

```
backend/                微服务多模块 Maven 工程
├── agent-gateway       网关（路由/鉴权/转发头注入）
├── tenant-service      租户/用户/权限/JWT 签发
├── agent-core-service  Agent 调度/多智能体/任务
├── rag-service         知识库/RAG（含 file 能力）
├── tool-service        工具执行/注册
├── task-job-service    定时任务
└── common              自定义 Starter 聚合
    ├── common-code
    ├── common-jwt-starter
    ├── common-redis-starter
    ├── common-mybatisplus-starter
    ├── common-model-starter
    ├── common-trace-starter
    ├── common-mq-starter
    ├── common-swagger-starter
    └── common-oss-starter
frontend/               Vue3 前端
docs/                   全套文档
docker-compose.yml      开源环境一键启动
```

微服务数量上限 6（网关不计入），禁止盲目拆分。

## 5. 代码规范

1. 分层命名：`controller / service / mapper / entity / dto / vo`
2. 统一返回 `R<T>`、全局异常、JSR303 参数校验（由 common-code 提供）
3. 金额计算**必须 Decimal**，严禁 float/double
4. 每个 Starter 必须带模块 README + 关键类注释
5. Controller 只做参数装配与返回，业务放 Service
6. 实体转换封装在实体类：新增用静态工厂 `from(...)`、更新用实例方法 `apply(...)`，**业务层禁止手写 set 组装实体**（转换发生在目标类，如 `ToolRegistry.from(request, tenantId)`、`AgentTask.from(request, tenantId)`、`AgentTaskStep.from(plan, tenantId, taskId, stepNo)`）
7. 禁止在代码里硬编码密钥/数据库密码
8. **实体数据访问收敛到实体自己的 Service**：每个实体的查询/更新**只允许出现在该实体对应的 Service 内**（Mapper 仅被其专属 Service 持有）；编排器 / Controller / MQ 消费者等外部组件需要数据时，**注入对应实体的 Service**，禁止直接持有不属于本类的 Mapper 或把其他实体的查询/更新实现写在外部类里（如 `AgentOrchestrator` 不得持有 `AgentTaskMapper`，步骤数据须经 `AgentTaskStepService`）
9. **批量新增与自定义 SQL 统一 XML 写法**：Mapper 接口加 `@Mapper`，**只声明方法签名**，SQL 一律写在 `src/main/resources/mapper/<XxxMapper>.xml`（namespace 为接口全限定名，`<insert id>` 与接口方法同名），**禁止 `@Insert` 注解内联 SQL**；**批量新增用单条多行 INSERT**（`<foreach>` 拼 VALUES），禁止 for 循环逐行 insert；JSON 列显式指定 `typeHandler=...JacksonTypeHandler`，`id` 自增、`created_at`/`updated_at` 走数据库默认值，`deleted` 显式写 0

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
