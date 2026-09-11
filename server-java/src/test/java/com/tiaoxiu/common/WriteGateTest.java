package com.tiaoxiu.common;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WriteGate 单元测试。
 *
 * <p>验证改造后的写操作网关语义：基于 {@code ReentrantLock.tryLock(超时)} 的
 * {@code tryAcquire()} / {@code release()}，以及连续写操作之间已移除冷却等待
 * （不再被冷却时间拖慢），且锁被占用时等待超时返回 false 而非无限阻塞。
 */
class WriteGateTest {

    private static final long TIMEOUT_MS = 150L;

    @Test
    @DisplayName("空闲时获取成功，释放后可再次获取")
    void acquireAndRelease() {
        WriteGate gate = new WriteGate(TIMEOUT_MS);
        Assertions.assertTrue(gate.tryAcquire(), "空闲状态应立即获取成功");
        gate.release();
        Assertions.assertTrue(gate.tryAcquire(), "释放后应可再次获取");
        gate.release();
    }

    @Test
    @DisplayName("回归：连续 20 次写操作不应被任何冷却拖慢")
    void noCooldownBetweenWrites() {
        WriteGate gate = new WriteGate(2000L);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; ++i) {
            Assertions.assertTrue(gate.tryAcquire());
            gate.release();
        }
        long cost = System.currentTimeMillis() - start;
        Assertions.assertTrue(cost < 2000L, "20 次连续写入耗时 " + cost + "ms，说明冷却已被移除");
    }

    @Test
    @DisplayName("锁被占用时：等待超时后返回 false，而不是无限阻塞")
    void timeoutThenGiveUp() throws Exception {
        WriteGate gate = new WriteGate(TIMEOUT_MS);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            Assertions.assertTrue(gate.tryAcquire());
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                gate.release();
            }
        }, "write-gate-holder");
        holder.start();
        Assertions.assertTrue(held.await(2L, TimeUnit.SECONDS), "持有线程应已拿到锁");
        long start = System.currentTimeMillis();
        boolean acquired = gate.tryAcquire();
        long cost = System.currentTimeMillis() - start;
        Assertions.assertFalse(acquired, "锁被别人持有时，超时后应返回 false");
        Assertions.assertTrue(cost >= TIMEOUT_MS, "至少应等待超时时间，实际 " + cost + "ms");
        Assertions.assertTrue(cost < 2000L, "不应无限等待，实际 " + cost + "ms");
        release.countDown();
        holder.join(2000L);
        Assertions.assertFalse(holder.isAlive(), "持有线程应已退出");
    }
}
