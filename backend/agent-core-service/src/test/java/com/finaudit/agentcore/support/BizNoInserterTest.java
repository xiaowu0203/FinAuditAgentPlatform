package com.finaudit.agentcore.support;

import com.finaudit.starter.web.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务单号撞库重试器单测。
 * <p>覆盖：首次成功不重试、撞号换号成功、重试前清主键、连续撞号抛 BizException、
 * 非撞号异常原样上抛（不吞真实故障）。</p>
 */
class BizNoInserterTest {

    private static final DuplicateKeyException COLLISION =
            new DuplicateKeyException("Duplicate entry 'R20260912005701 1854' for key 'uk_reimb_no'");

    /** 每次生成不同单号：NO-1、NO-2、NO-3... */
    private static AtomicInteger seq = new AtomicInteger();

    private static String nextNo() {
        return "NO-" + seq.incrementAndGet();
    }

    @Test
    void firstAttemptSuccessNoRetry() {
        AtomicInteger insertCount = new AtomicInteger();
        List<String> applied = new ArrayList<>();

        BizNoInserter.insertWithRetry("报销单", "报销单号", BizNoInserterTest::nextNo,
                applied::add, () -> { }, () -> insertCount.incrementAndGet());

        assertEquals(1, insertCount.get(), "首次成功不应重试");
        assertEquals(1, applied.size());
    }

    @Test
    void collisionRegeneratesNumberAndSucceeds() {
        AtomicInteger insertCount = new AtomicInteger();
        List<String> applied = new ArrayList<>();

        BizNoInserter.insertWithRetry("报销单", "报销单号", BizNoInserterTest::nextNo,
                applied::add, () -> { }, () -> {
                    if (insertCount.incrementAndGet() == 1) {
                        throw COLLISION;
                    }
                });

        assertEquals(2, insertCount.get(), "撞号后应重试一次");
        assertEquals(2, applied.size(), "重试须换新单号");
        assertTrue(applied.get(0).startsWith("NO-") && !applied.get(0).equals(applied.get(1)),
                "重试的单号必须与首次不同: " + applied);
    }

    @Test
    void collisionClearsBackfilledIdBeforeRetry() {
        AtomicInteger insertCount = new AtomicInteger();
        AtomicReference<Long> entityId = new AtomicReference<>(null);

        BizNoInserter.insertWithRetry("报销单", "报销单号", BizNoInserterTest::nextNo,
                no -> { }, () -> entityId.set(null), () -> {
                    if (insertCount.incrementAndGet() == 1) {
                        // 模拟 INSERT 失败时自增主键已被回填
                        entityId.set(999L);
                        throw COLLISION;
                    }
                    assertNull(entityId.get(), "重试前必须清空首轮回填的主键");
                });

        assertEquals(2, insertCount.get());
    }

    @Test
    void persistentCollisionThrowsBizException() {
        AtomicInteger insertCount = new AtomicInteger();

        BizException ex = assertThrows(BizException.class, () ->
                BizNoInserter.insertWithRetry("任务", "任务号", BizNoInserterTest::nextNo,
                        no -> { }, () -> { }, () -> {
                            insertCount.incrementAndGet();
                            throw COLLISION;
                        }));

        assertEquals(3, insertCount.get(), "应尝试满 3 次后放弃");
        assertTrue(ex.getMessage().contains("稍后重试"), "异常文案应可读: " + ex.getMessage());
    }

    @Test
    void nonCollisionExceptionPropagatesUntouched() {
        AtomicInteger insertCount = new AtomicInteger();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BizNoInserter.insertWithRetry("任务", "任务号", BizNoInserterTest::nextNo,
                        no -> { }, () -> { }, () -> {
                            insertCount.incrementAndGet();
                            throw new IllegalStateException("Data too long for column 'title'");
                        }));

        assertEquals(1, insertCount.get(), "非撞号异常不得重试");
        assertTrue(ex.getMessage().contains("Data too long"));
    }
}
