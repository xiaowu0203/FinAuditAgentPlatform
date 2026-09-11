package com.finaudit.starter.redis.config;

import com.finaudit.starter.redis.lock.DistributedLockTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 公共Redis自动装配配置类
 * <p>
 * 提供能力：
 * <ul>
 *     <li>1. 自定义RedisTemplate：key使用String序列化，value使用Jackson JSON序列化</li>
 *     <li>2. RedissonClient（分布式锁）：由 spring.data.redis.* 显式构建，
 *         与 RedisTemplate 共用同一 Redis 实例配置</li>
 *     <li>3. 分布式锁模板 DistributedLockTemplate Bean，用于业务并发控制，支持普通锁 / 事务后置释放锁</li>
 * </ul>
 * 触发条件：classpath存在RedisTemplate才会加载该自动配置
 * </p>
 * <p><b>⚠️ P3.8 关键修复（R0-10）</b>：本 starter 不再引入 <code>redisson-spring-boot-starter</code>，
 * 只引 redisson 核心包。原因见 pom.xml 注释——starter 传递的 <code>redisson-spring-data-34</code>
 * 与 spring-data-redis 3.5.0 不兼容（缺少新增抽象方法 <code>pExpire(byte[], long, Condition)</code>），
 * 一旦它注册的 <code>RedissonConnectionFactory</code> 生效，任何设置 TTL 的操作
 * 都会在 <code>DefaultedRedisConnection.pExpire</code> 上无限递归并抛 <code>StackOverflowError</code>。
 * 现在连接工厂统一交回 Spring Boot 默认的 Lettuce 实现（<code>LettuceKeyCommands</code> 已适配 3.5.0）。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)
public class CommonRedisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommonRedisAutoConfiguration.class);

    /** Redisson 单机地址协议前缀（TLS 时使用 rediss://） */
    private static final String REDIS_SCHEME = "redis://";
    private static final String REDIS_TLS_SCHEME = "rediss://";

    /**
     * 构建RedisTemplate Bean
     * <p>
     * key/hashKey：StringRedisSerializer 字符串序列化
     * value/hashValue：GenericJackson2JsonRedisSerializer json序列化，存入带类型信息，方便反序列化对象
     * </p>
     * <p>
     * {@code @ConditionalOnMissingBean(name = "redisTemplate")}：如果容器已经有名为redisTemplate的Bean，则不覆盖，不重复创建
     * </p>
     * @param connectionFactory Redis连接工厂，由spring‑data‑redis自动装配（Lettuce）
     * @return 配置完成的RedisTemplate
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // key序列化器：字符串
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        // value序列化器：Jackson JSON序列化，支持对象序列化与反序列化
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        // 初始化后置处理，应用序列化配置
        template.afterPropertiesSet();
        return template;
    }

    /**
     * RedissonClient Bean：由 {@code spring.data.redis.*} 配置显式构建（原由 starter 自动装配）。
     * <p>仅用于分布式锁（{@link DistributedLockTemplate}）；<b>不</b>注册 RedisConnectionFactory，
     * 以免顶掉 Lettuce 连接工厂（见类注释 R0-10）。</p>
     * <p>空密码归一化：部分环境把密码配成空字符串，Redisson 会把它当真实密码提交导致认证失败，
     * 此处统一转 null 表示不传密码（沿用原 starter 自定义器的语义）。</p>
     *
     * @param properties Spring Boot 标准 Redis 配置（host/port/password/database/timeout/ssl）
     * @return 已连接的 RedissonClient（@Bean 由容器管理生命周期，destroyMethod 自动 shutdown）
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        String scheme = properties.getSsl().isEnabled() ? REDIS_TLS_SCHEME : REDIS_SCHEME;
        String address = scheme + properties.getHost() + ":" + properties.getPort();

        var single = config.useSingleServer()
                .setAddress(address)
                .setDatabase(properties.getDatabase());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            single.setUsername(properties.getUsername());
        }
        String password = properties.getPassword();
        if (password != null && !password.isEmpty()) {
            single.setPassword(password);
        }
        // 命令超时：spring.data.redis.timeout（缺省 2s 由 Boot 提供），Redisson 单位毫秒
        Duration timeout = properties.getTimeout();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            single.setTimeout((int) timeout.toMillis());
        }

        log.info("初始化 RedissonClient（分布式锁）：address={}, database={}", address, properties.getDatabase());
        return Redisson.create(config);
    }

    /**
     * Redisson分布式锁模板Bean
     * <p>
     * 用于P3b审批工单等业务并发控制，封装Redisson分布式锁能力。
     * <ul>
     *     <li>execute：普通分布式锁，业务执行完毕立即释放锁，不感知事务</li>
     *     <li>executeInTx：事务感知锁，存在Spring事务上下文时，锁延迟至事务提交/回滚完成后再释放，消除锁提前释放与事务提交的时间窗口</li>
     * </ul>
     * </p>
     * <p>
     * 条件：classpath存在RedissonClient，且容器中不存在DistributedLockTemplate Bean时才创建，允许业务自定义覆盖。
     * </p>
     *
     * @param redissonClient redisson客户端实例
     * @return 分布式锁模板实例
     */
    @Bean
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnMissingBean
    public DistributedLockTemplate distributedLockTemplate(RedissonClient redissonClient) {
        return new DistributedLockTemplate(redissonClient);
    }
}
