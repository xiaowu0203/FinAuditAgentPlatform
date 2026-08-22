package com.finaudit.starter.redis.config;

import com.finaudit.starter.redis.lock.DistributedLockTemplate;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 公共Redis自动装配配置类
 * <p>
 * 提供能力：
 * <ul>
 *     <li>1. Redisson配置自定义器：处理空密码问题，避免空字符串密码导致Redis认证失败</li>
 *     <li>2. 自定义RedisTemplate：key使用String序列化，value使用Jackson JSON序列化</li>
 *     <li>3. 分布式锁模板 DistributedLockTemplate Bean，用于业务并发控制，支持普通锁 / 事务后置释放锁</li>
 * </ul>
 * 触发条件：classpath存在RedisTemplate才会加载该自动配置
 * </p>
 */
@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)
public class CommonRedisAutoConfiguration {

    /**
     * Redisson自定义配置器：空密码归一化处理器
     * <p>
     * 问题背景：部分配置文件中redis密码配置为空字符串 ""，Redisson会将空字符串当作密码提交给Redis服务端，
     * Redis服务端密码为空时认证会报错。此处把空字符串密码修正为null，代表不传递密码。
     * </p>
     * <p>生效前提：classpath存在RedissonAutoConfigurationCustomizer、RedissonClient</p>
     *
     * @return Redisson配置自定义回调
     */
    @Bean
    @ConditionalOnClass({RedissonAutoConfigurationCustomizer.class, RedissonClient.class})
    public RedissonAutoConfigurationCustomizer redissonEmptyPasswordNormalizer() {
        return config -> {
            if (config.useSingleServer().getPassword() != null
                    && config.useSingleServer().getPassword().isEmpty()) {
                config.useSingleServer().setPassword(null);
            }
        };
    }

    /**
     * 构建RedisTemplate Bean
     * <p>
     * key/hashKey：StringRedisSerializer 字符串序列化
     * value/hashValue：GenericJackson2JsonRedisSerializer json序列化，存入带类型信息，方便反序列化对象
     * </p>
     * <p>
     * {@code @ConditionalOnMissingBean(name = "redisTemplate")}：如果容器已经有名为redisTemplate的Bean，则不覆盖，不重复创建
     * </p>
     * @param connectionFactory Redis连接工厂，由spring‑data‑redis自动装配
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
     * Redisson分布式锁模板Bean
     * <p>
     * 用于P3b审批工单等业务并发控制，封装Redisson分布式锁能力。
     * <ul>
     *     <li>execute：普通分布式锁，业务执行完毕立即释放锁，不感知事务</li>
     *     <li>executeInTx：事务感知锁，存在Spring事务上下文时，锁延迟至事务提交/回滚完成后再释放，消除锁提前释放与事务提交的时间窗口</li>
     * </ul>
     * 依赖redisson‑spring‑boot‑starter自动装配的RedissonClient，复用 spring.data.redis.* 的Redis连接配置，与RedisTemplate共用同一Redis实例。
     * </p>
     * <p>
     * 条件：classpath存在RedissonClient，且容器中不存在DistributedLockTemplate Bean时才创建，允许业务自定义覆盖。
     * </p>
     *
     * @param redissonClient redisson客户端实例，由redisson starter自动注入
     * @return 分布式锁模板实例
     */
    @Bean
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnMissingBean
    public DistributedLockTemplate distributedLockTemplate(RedissonClient redissonClient) {
        return new DistributedLockTemplate(redissonClient);
    }
}
