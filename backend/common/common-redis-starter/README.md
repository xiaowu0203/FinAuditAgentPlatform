# common-redis-starter

Redis 通用能力自动装配。

## 能力
- `RedisTemplate<String, Object>` 统一配置：key String / value JSON（GenericJackson2JsonRedisSerializer）
- `DistributedLockTemplate`：Redisson 分布式锁模板（**P3b 已落地**，见下）

## DistributedLockTemplate（分布式锁）
- 依赖 `redisson-spring-boot-starter`（版本由 `backend/pom.xml` dependencyManagement 锁定 3.47.0），自动装配读取 `spring.data.redis.*` 前缀（Boot 3.5 约定，与 RedisTemplate 同一连接）
- 用法：`lockTemplate.execute("audit:ticket:1", () -> doAction())`，锁 key 自动拼接前缀 `finaudit:lock:`；默认等待 5s / 持有 30s，超时或中断统一抛 `BizException`
- 防御性释放：finally 中 `isHeldByCurrentThread()` 校验所有权，租约过期后不误释放他人锁
- 落地场景：P3b 审批工单并发控制（同一工单多人同时审批），见 `agent-core` `AuditTicketService.action`

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-redis-starter</artifactId>
</dependency>
```

## 规划
- P1：临时会话上下文缓存
