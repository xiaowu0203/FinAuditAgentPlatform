# common-redis-starter

Redis 通用能力自动装配。

## 能力
- `RedisTemplate<String, Object>` 统一配置：key String / value JSON（GenericJackson2JsonRedisSerializer）

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-redis-starter</artifactId>
</dependency>
```

## 规划
- P1：分布式锁（Redisson）
- P1：临时会话上下文缓存
