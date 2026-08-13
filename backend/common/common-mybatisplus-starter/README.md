# common-mybatisplus-starter

MyBatis-Plus 通用能力自动装配。

## 能力
- `MybatisPlusInterceptor` + 分页插件（MySQL）

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-mybatisplus-starter</artifactId>
</dependency>
```
各服务需自行提供 `@MapperScan` 与数据源配置。

## 规划
- P1：多租户行级隔离插件 `TenantLineInnerInterceptor`（禁止跨租户数据泄露）
- P1：逻辑删除配置
