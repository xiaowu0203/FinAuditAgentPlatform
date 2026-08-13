# common-code

Web 通用能力自动装配（Servlet Web 环境）。

## 能力
- 统一返回 `R<T>`（`com.finaudit.starter.web.result.R`）：`R.success()` / `R.fail(code, msg)`
- 业务异常 `BizException` + 全局异常处理器 `GlobalExceptionHandler`
- JSR303 参数校验异常统一转换

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-code</artifactId>
</dependency>
```

> 注意：本 Starter 依赖 Servlet Web，**网关（WebFlux）不要引入**。
