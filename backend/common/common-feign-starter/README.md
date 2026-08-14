# common-feign-starter

服务间同步调用统一能力：**OpenFeign 请求头/token 透传**。

## 解决的问题

微服务间需要同步查询（如 agent-core 规划器拉取 tool-service 工具目录）时，鉴权与租户/用户上下文
必须跨服务传递。本 starter 统一自动注册 **请求头透传拦截器**：把当前请求的 `Authorization`（token）、
`X-Tenant-Id`、`X-User-Id`、`X-Username`、`X-User-Roles`、`X-Trace-Id` 透传到 Feign 出站请求
（HTTP 请求链场景）。

> OpenFeign 依赖与**跨服务 Feign 契约**（客户端接口 + DTO）统一由 common-code 提供
> （`com.finaudit.starter.web.feign`），本 starter 只负责「怎么调」的透传能力。

## 使用方式

1. 服务 pom 引入（消费方）：
   ```xml
   <dependency>
       <groupId>com.finaudit</groupId>
       <artifactId>common-feign-starter</artifactId>
       <version>${project.version}</version>
   </dependency>
   ```
2. Feign 契约已在 common-code 自动注册，服务依赖 common-code 后直接注入使用：
   ```java
   @Autowired
   private ToolServiceFeign toolServiceFeign;   // 契约定义见 common-code
   ```

## 注意事项

- **非 HTTP 上下文**（如 MQ 消费线程）：无请求上下文，拦截器自动跳过，不会注入脏头；
  业务需显式传递（如 `X-Tenant-Id` 头）。
- **服务间安全**：Feign 调用经 Nacos 服务名直连（不经网关）；对外鉴权仅靠网关，服务间互信。
  契约复用对外接口，租户/身份经请求头显式传递。
