# common-starter 通用能力聚合模块

各子 Starter 依赖本模块。使用方式：业务服务按需引入子 Starter 依赖即可自动装配。

| Starter | 能力 | 状态 |
|---|---|---|
| [common-web-starter](common-web-starter/README.md) | 统一返回 R&lt;T&gt; / 全局异常 / JSR303 校验 | P0 可用 |
| [common-redis-starter](common-redis-starter/README.md) | RedisTemplate JSON 序列化 | P0 可用，锁 P1 |
| [common-mybatisplus-starter](common-mybatisplus-starter/README.md) | MyBatis-Plus 分页插件 | P0 可用，多租户 P1 |
| [common-model-starter](common-model-starter/README.md) | 多模型统一抽象 | P0 骨架，P1 实现 |
| [common-trace-starter](common-trace-starter/README.md) | traceId 生成/透传 | P0 可用，SkyWalking P4 |
