package com.finaudit.starter.redis.lock;

import com.finaudit.starter.web.exception.BizException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson分布式锁模板工具类
 * <p>提供两种锁执行模式：
 * <ul>
 *     <li>execute：普通分布式锁，业务代码执行完毕finally立即释放锁</li>
 *     <li>executeInTx：事务感知分布式锁，存在事务上下文时，锁延迟到事务提交/回滚完成之后才释放；无事务退化为普通锁</li>
 * </ul>
 * 核心解决痛点：数据库事务还未提交，分布式锁已经释放，其他线程获取锁读到未提交数据，引发重复操作、脏数据问题。
 * </p>
 * <p>注意：
 * <li>1. leaseSeconds 锁过期时间，必须大于业务最大执行时间，防止业务没跑完锁过期；</li>
 * <li>2. executeInTx 必须在Spring @Transactional事务上下文内才会生效延迟释放；</li>
 * <li>3. 锁释放做线程归属校验，不会释放其他线程持有的锁；</li>
 * <li>4. 获取锁超时、线程中断统一抛出业务异常，上层自行捕获处理。</li>
 * </p>
 */
public class DistributedLockTemplate {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockTemplate.class);

    /**
     * Redis锁key全局前缀
     * 隔离业务其他Redis key，防止key名冲突覆盖
     */
    public static final String KEY_PREFIX = "finaudit:lock:";
    /**
     * 默认获取锁最大等待时间，单位：秒
     * 并发抢锁时最多等待多久拿锁，超时直接失败
     */
    private static final long DEFAULT_WAIT_SECONDS = 5;
    /**
     * 默认锁租约过期时间，单位：秒
     * 看门狗续期：Redisson自动续期，业务卡死防止死锁；
     * 如果业务执行时间超过该时间锁会自动过期释放。
     */
    private static final long DEFAULT_LEASE_SECONDS = 30;

    private final RedissonClient redissonClient;

    public DistributedLockTemplate(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 普通分布式锁执行，有返回值，使用默认等待时间、默认租约时间
     * <p>执行完业务逻辑，finally立即释放锁，不感知数据库事务</p>
     * @param lockKey 业务锁key，不需要带前缀，内部自动拼接 KEY_PREFIX
     * @param action  需要锁保护的业务逻辑
     * @return 业务逻辑返回结果
     * @throws BizException 获取锁超时 / 线程被中断抛出业务异常
     */
    public <T> T execute(String lockKey, Supplier<T> action) {
        return execute(lockKey, DEFAULT_WAIT_SECONDS, DEFAULT_LEASE_SECONDS, action);
    }

    /**
     * 普通分布式锁执行，无返回值，使用默认等待时间、默认租约时间
     * @param lockKey 业务锁key
     * @param action  无返回业务逻辑
     */
    public void execute(String lockKey, Runnable action) {
        execute(lockKey, DEFAULT_WAIT_SECONDS, DEFAULT_LEASE_SECONDS, action);
    }

    /**
     * 普通分布式锁执行，自定义等待时间、租约时间，有返回值
     * <p>业务执行完成后finally块立刻释放锁，不管数据库事务是否提交。
     * <b>⚠️注意：如果方法内部有数据库事务，不要使用该方法，优先使用 executeInTx，避免锁释放早于事务提交产生并发漏洞</b>
     * </p>
     *
     * @param lockKey      业务锁key，内部拼接锁前缀
     * @param waitSeconds  获取锁最大等待时间(秒)，超时拿不到锁抛异常
     * @param leaseSeconds 锁租约过期时间(秒)，业务卡死自动释放防死锁
     * @param action       受锁保护的业务逻辑
     * @return T 业务返回值
     * @throws BizException 获取锁超时、线程中断抛出业务异常
     */
    public <T> T execute(String lockKey, long waitSeconds, long leaseSeconds, Supplier<T> action) {
        // 拼接完整redis锁key
        String fullKey = KEY_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        // 标记是否当前线程成功拿到锁
        boolean locked = false;
        try {
            // tryLock：尝试获取锁，waitSeconds最多等待，leaseSeconds锁持有过期时间
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取分布式锁超时: {}", fullKey);
                throw new BizException("系统繁忙，请稍后重试（操作并发冲突）");
            }
            // 获取锁成功，执行业务逻辑
            return action.get();
        } catch (InterruptedException e) {
            // 线程被中断，恢复中断标记
            Thread.currentThread().interrupt();
            log.warn("获取分布式锁被中断: {}", fullKey, e);
            throw new BizException("系统繁忙，请稍后重试（操作被中断）");
        } finally {
            // 只要拿到锁，无论业务正常/异常，都释放锁
            if (locked) {
                releaseQuietly(lock);
            }
        }
    }

    /**
     * 事务感知分布式锁，有返回值，使用默认等待、租约时间
     * <p>存在Spring事务上下文时，锁延迟释放，等事务commit/rollback完成之后才释放锁</p>
     * @param lockKey 业务锁key
     * @param action 业务逻辑
     * @return 业务返回值
     */
    public <T> T executeInTx(String lockKey, Supplier<T> action) {
        return executeInTx(lockKey, DEFAULT_WAIT_SECONDS, DEFAULT_LEASE_SECONDS, action);
    }

    /**
     * 事务感知分布式锁，无返回值，使用默认等待、租约时间
     * @param lockKey 业务锁key
     * @param action 无返回业务逻辑
     */
    public void executeInTx(String lockKey, Runnable action) {
        executeInTx(lockKey, DEFAULT_WAIT_SECONDS, DEFAULT_LEASE_SECONDS, () -> {
            action.run();
            return null;
        });
    }


    /**
     * 【事务安全版分布式锁】自定义超时时间
     * <p>
     * ✅核心逻辑：
     * <li>1. 当前线程存在Spring事务上下文{@code @Transactional}：注册事务同步回调，锁不在finally释放，延迟到事务afterCompletion（提交/回滚全部结束）才释放锁</li>
     * <li>2. 当前线程没有事务上下文：行为退化成普通execute，finally立刻释放锁</li>
     * </p>
     * <p>
     * 🎯解决经典并发漏洞：
     * 若使用普通锁：业务执行完 → finally释放分布式锁 → 数据库事务还没提交。
     * 其他请求立刻拿到锁，读取数据库未提交的数据，造成重复新增、重复审批、重复留痕。
     * executeInTx 将锁释放时机后置，保证事务提交完毕，其他线程才能拿到锁。
     * </p>
     *
     * @param lockKey      业务锁key，内部拼接锁前缀
     * @param waitSeconds  获取锁等待时间(秒)
     * @param leaseSeconds 锁租约过期时间(秒)
     * @param action       受锁保护业务逻辑（内部建议包含DB写操作）
     * @return 业务返回结果
     * @throws BizException 获取锁超时、线程中断抛出业务异常
     */
    public <T> T executeInTx(String lockKey, long waitSeconds, long leaseSeconds, Supplier<T> action) {
        // 拼接完整redis锁key
        String fullKey = KEY_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        // 标记是否当前线程成功拿到锁
        boolean locked = false;
        try {
            // 尝试抢占分布式锁
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取分布式锁超时: {}", fullKey);
                throw new BizException("系统繁忙，请稍后重试（操作并发冲突）");
            }

            // 判断当前是否处于Spring事务上下文
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                // 注册事务回调：事务提交 OR 回滚完成之后执行释放锁
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        // status: STATUS_COMMITTED / STATUS_ROLLED_BACK / STATUS_UNKNOWN
                        releaseQuietly(lock);
                    }
                });
                // 执行业务逻辑，finally中不会释放锁，交给事务回调释放
                return action.get();
            }

            // 无事务上下文，直接执行业务，走finally释放
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取分布式锁被中断: {}", fullKey, e);
            throw new BizException("系统繁忙，请稍后重试（操作被中断）");
        } finally {
            // 分支判断：
            // 有事务上下文：锁交给afterCompletion回调释放，此处不释放！
            // 无事务上下文：此处finally直接释放锁
            if (locked && !TransactionSynchronizationManager.isSynchronizationActive()) {
                releaseQuietly(lock);
            }
        }
    }

    /**
     * 普通分布式锁，无返回值，自定义等待时间、租约时间
     * @param lockKey      业务锁key
     * @param waitSeconds  获取锁等待秒数
     * @param leaseSeconds 锁租约过期秒数
     * @param action       无返回业务逻辑
     */
    public void execute(String lockKey, long waitSeconds, long leaseSeconds, Runnable action) {
        execute(lockKey, waitSeconds, leaseSeconds, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 安全释放锁工具方法
     * <p>关键点：isHeldByCurrentThread() 判断锁是否属于当前线程，防止释放已经过期/其他线程持有的锁</p>
     * <p>捕获所有异常，解锁失败只打印warn日志，不向上抛业务异常，避免业务逻辑因为解锁失败而报错</p>
     * @param lock redisson锁对象
     */
    private void releaseQuietly(RLock lock) {
        try {
            // 只有锁属于当前线程，才执行unlock解锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放分布式锁异常: {}", e.getMessage());
        }
    }
}
