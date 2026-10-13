package com.finaudit.agentcore.support;

import com.finaudit.starter.web.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 业务单号落库重试器。
 * <p>报销单号（{@code uk_reimb_no}）与任务号（{@code uk_task_no}）均为
 * 「前缀 + yyyyMMddHHmmss + 4 位随机数」，同一秒并发提交时随机段存在碰撞概率
 * （同一秒 N 笔的碰撞概率约 {@code C(N,2)/10000}）。撞库会抛
 * {@link DuplicateKeyException}，被全局异常处理器兜成用户可见的
 * 「数据唯一约束冲突」。本类在碰撞时<b>重新生成单号重试</b>，避免把内部编码细节
 * 暴露成业务失败。</p>
 *
 * <p>只吞掉单号撞库本身：连续 {@value #MAX_ATTEMPTS} 次仍冲突才抛
 * {@link BizException}；其余数据库异常（字段超长、非空约束等）原样上抛，不掩盖真实问题。</p>
 */
public final class BizNoInserter {

    private static final Logger log = LoggerFactory.getLogger(BizNoInserter.class);

    /** 单号碰撞重试上限（含首次尝试共 3 次） */
    private static final int MAX_ATTEMPTS = 3;

    private BizNoInserter() {
    }

    /**
     * 生成单号落库，撞号自动换号重试。
     *
     * @param entityLabel    实体中文名（日志/异常文案，如「报销单」）
     * @param numberLabel    单号字段中文名（如「报销单号」）
     * @param numberSupplier 单号生成器，每次调用须返回新的候选单号
     * @param numberApplier  单号写回实体（如 {@code reimb::setReimbNo}）
     * @param idClearer      次轮重试前清除首轮自增回填的主键（传 {@code null} 表示实体无自增主键）
     * @param inserter       落库动作（如 {@code () -> mapper.insert(reimb)}）
     * @throws BizException 连续 {@value #MAX_ATTEMPTS} 次均撞号时抛出
     */
    public static void insertWithRetry(String entityLabel, String numberLabel,
                                       Supplier<String> numberSupplier,
                                       Consumer<String> numberApplier,
                                       Runnable idClearer,
                                       Runnable inserter) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String no = numberSupplier.get();
            numberApplier.accept(no);
            try {
                inserter.run();
                if (attempt > 1) {
                    log.info("{}号碰撞换号成功：{}={}（第 {} 次尝试）", entityLabel, numberLabel, no, attempt);
                }
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("{}号连续 {} 次碰撞，放弃重试：{}={}", entityLabel, MAX_ATTEMPTS, numberLabel, no);
                    throw new BizException("系统繁忙，请稍后重试");
                }
                log.warn("{}号碰撞，换号重试（第 {} 次）：{}={}", entityLabel, attempt + 1, numberLabel, no);
                // 首轮 INSERT 失败时自增主键可能已被回填，重试前必须清空，否则按主键写入冲突行
                if (idClearer != null) {
                    idClearer.run();
                }
            }
        }
    }
}
