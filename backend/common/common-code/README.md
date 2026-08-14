# common-code

Web 通用能力自动装配（Servlet Web 环境）。

## 能力
- 统一返回 `R<T>`（`com.finaudit.starter.web.result.R`）：`R.success()` / `R.fail(code, msg)`
- 业务异常 `BizException` + 全局异常处理器 `GlobalExceptionHandler`
- JSR303 参数校验异常统一转换
- 跨服务 Feign 契约（`com.finaudit.starter.web.feign`）：客户端接口 + `dto` 下共享 DTO，自动注册

## 跨服务 Feign 契约
- 客户端接口与跨服务 DTO 统一放本模块（`com.finaudit.starter.web.feign`），任何服务引入 common-code
  即自动注册 `@FeignClient`（无需各自写一遍）。命名约定：`工程名 + Feign`，如 `ToolServiceFeign`。
- 契约复用服务的对外接口（如 tool-service `GET /api/v1/tools`），服务间经 Nacos 服务名直连（不经网关）；
  租户/身份经请求头（如 `X-Tenant-Id`）显式传递，由 Feign 方法参数声明。
- 请求头/token 透传由 common-feign-starter 提供，消费方需一并引入。

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-code</artifactId>
</dependency>
```

> 注意：本 Starter 依赖 Servlet Web + OpenFeign，**网关（WebFlux）不要引入**。
