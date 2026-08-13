# common-trace-starter

链路追踪通用能力（Servlet Web 环境）。

## 能力
- `TraceIdFilter`：生成 / 透传 `X-Trace-Id`，写入 MDC（key=`traceId`）
- `TraceContextHolder`：线程内获取 traceId
- 日志 pattern 加 `%X{traceId}` 即可按链路检索

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-trace-starter</artifactId>
</dependency>
```

## 规划
- P4：配合 SkyWalking 全链路追踪（网关请求 → Agent 调度 → 工具调用 → 模型 API → DB）
