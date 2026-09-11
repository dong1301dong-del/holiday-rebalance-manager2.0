package com.tiaoxiu.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全局写操作网关：保证「同一时间仅允许一条增删改」串行执行。
 *
 * <p>设计要点：
 * <ul>
 *   <li>用可重入锁把并发写请求串行化，任意时刻只有一个写请求在执行业务逻辑；</li>
 *   <li>{@link #tryAcquire()} 尝试在限定时间内拿到锁，超时（或线程被中断）则直接失败，
 *       交由调用方返回「系统繁忙」，而不是无限阻塞；</li>
 *   <li>{@link #release()} 在写请求处理完毕后（无论成功或异常）释放锁；</li>
 *   <li>读请求（GET）不经过本网关，互不影响。</li>
 * </ul>
 *
 * <p>由 {@code WriteGuardInterceptor} 在写请求的前后置钩子里成对调用，
 * 不需要在每个 Service 写方法里手动包裹，避免散落与遗漏。
 */
@Component
public class WriteGate {

    /** 尝试获取写锁的最长等待时间（毫秒），可通过配置项 app.write-lock-timeout-ms 调整，默认 3000 */
    private final long lockTimeoutMs;

    private final ReentrantLock lock = new ReentrantLock();

    public WriteGate(@Value("${app.write-lock-timeout-ms:3000}") long lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
    }

    /**
     * 进入写操作前调用：在 {@code lockTimeoutMs} 内尝试获取串行锁。
     *
     * @return true 表示拿到锁（调用方必须配对调用 {@link #release()}）；false 表示超时或线程被中断
     */
    public boolean tryAcquire() {
        try {
            return lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 等待被中断时恢复中断标记并返回失败，不阻塞业务线程
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 写操作完成后调用：释放串行锁。务必在 finally 中调用。 */
    public void release() {
        lock.unlock();
    }
}
