# common-swagger-starter

Springdoc（Swagger/OpenAPI 3）通用能力自动装配。

## 能力
- 引入 `springdoc-openapi-starter-webmvc-ui`，开箱即用：
  - Swagger UI：`http://localhost:<port>/swagger-ui.html`
  - OpenAPI 文档：`http://localhost:<port>/v3/api-docs`
- 自动装配统一 `OpenAPI` 元信息 Bean（title/description/version）

> 仅支持 WebMVC（Spring Cloud 网关为 WebFlux，勿引入，需另用 webflux 变体）。

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-swagger-starter</artifactId>
  <version>${project.version}</version>
</dependency>
```

配置（可省略，均有默认值）：
```yaml
finaudit:
  swagger:
    enabled: true            # false 关闭 OpenAPI 装配（默认 true）
    title: agent-core-service API
    description: 任务调度与多智能体编排接口
    version: 0.1.0
```

## 生产环境关闭
仅依赖方自己引入 starter 还不够关闭对外暴露，需同时：
```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

## 备注
- 版本由根 pom `dependencyManagement` 统一锁定（springdoc-openapi 2.8.13，适配 Spring Boot 3.5.x）。
