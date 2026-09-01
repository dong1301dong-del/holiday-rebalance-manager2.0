package com.tiaoxiu.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 全局写操作网关：保证「同一时间仅允许一条增删改」，且每次写操作落库完成后，
 * 至少间隔 {@code cooldownMs} 才允许下一次写操作。
 *
 * <p>设计要点：
 * <ul>
 *   <li>用可重入锁把并发写请求串行化，任意时刻只有一个写请求在执行业务逻辑；</li>
 *   <li>{@link #acquire()} 在拿到锁后，若距上次写完成不足冷却时间则阻塞等待；</li>
 *   <li>{@link #release()} 在写请求处理完毕后（无论成功或异常）记录完成时间并释放锁；</li>
 *   <li>读请求（GET）不经过本网关，互不影响。</li>
 * </ul>
 *
 * <p>由 {@code WriteGuardInterceptor} 在写请求的前后置钩子里成对调用，
 * 不需要在每个 Service 写方法里手动包裹，避免散落与遗漏。
 */
@Component
public class WriteGate {

    /** 写操作之间的最小冷却间隔（毫秒），可通过配置项 app.write-cooldown-ms 调整，默认 3000 */
    private final long cooldownMs;

    private final ReentrantLock lock = new ReentrantLock();
    /** 上一次写操作完成的时间戳；首次为 0，保证首个写请求无需等待 */
    private volatile long lastWriteDoneMillis = 0;

    public WriteGate(@Value("${app.write-cooldown-ms:3000}") long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    /**
     * 进入写操作前调用：获取串行锁，并按需等待冷却时间。
     * 必须在 {@link #release()} 中配对释放；本方法内部已对异常路径做释放保护。
     */
    public void acquire() {
        lock.lock();
        try {
            long wait = cooldownMs - (System.currentTimeMillis() - lastWriteDoneMillis);
            while (wait > 0) {
                Thread.sleep(wait);
                wait = cooldownMs - (System.currentTimeMillis() - lastWriteDoneMillis);
            }
        } catch (InterruptedException e) {
            // 等待被中断时恢复中断标记并立即放行，不阻塞业务
            Thread.currentThread().interrupt();
        }
    }

    /** 写操作完成后调用：记录完成时间并释放串行锁。务必在 finally 中调用。 */
    public void release() {
        try {
            lastWriteDoneMillis = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
    }
}
