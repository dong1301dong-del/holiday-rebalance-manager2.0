package com.tiaoxiu.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 IP 限流：按「IP + 滑动窗口」统计登录尝试次数，超过阈值后拒绝该 IP 继续登录一段时间，
 * 以缓解暴力破解。纯内存实现，适用于单实例；多实例部署需改用共享存储（如 Redis）。
 */
@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** 单个窗口内允许的最大登录尝试次数。 */
    @Value("${app.login-ip-limit:30}")
    private int maxAttempts;

    /** 限流窗口长度（秒）。 */
    @Value("${app.login-ip-window-seconds:300}")
    private int windowSeconds;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 尝试获取一次登录配额。
     *
     * @param ip 客户端 IP；为空则放行（无法定位来源时不做限制）
     * @return 仍允许登录返回 true，已达上限返回 false
     */
    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis() / 1000L;
        Window w = this.windows.compute(ip, (k, old) -> {
            if (old == null || now - old.startEpochSecond >= (long) this.windowSeconds) {
                return new Window(now);
            }
            ++old.count;
            return old;
        });
        return w.count < this.maxAttempts;
    }

    /** 登录成功后清除该 IP 的限流计数。 */
    public void reset(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        this.windows.remove(ip);
    }

    /** 定期清理过期的限流窗口（超过两倍窗口时长即视为失效）。 */
    @Scheduled(fixedDelay = 600000L)
    public void cleanup() {
        try {
            long now = System.currentTimeMillis() / 1000L;
            this.windows.entrySet().removeIf(e -> now - e.getValue().startEpochSecond >= (long) this.windowSeconds * 2L);
        } catch (Exception e) {
            log.warn("清理登录限流窗口失败：{}", e.getMessage());
        }
    }

    public int getMaxAttempts() {
        return this.maxAttempts;
    }

    /** 单个 IP 的限流窗口：记录窗口起点与累计尝试次数。 */
    private static final class Window {
        volatile long startEpochSecond;
        volatile int count;

        Window(long startEpochSecond) {
            this.startEpochSecond = startEpochSecond;
        }
    }
}
